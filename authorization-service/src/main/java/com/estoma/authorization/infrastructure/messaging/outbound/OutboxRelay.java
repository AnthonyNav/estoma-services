package com.estoma.authorization.infrastructure.messaging.outbound;

import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.OutboxRepository.OutboxMessage;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.MessagingNames;
import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authorization.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims in a short transaction, publishes outside it, then fences the state transition. */
@Component
public final class OutboxRelay {

  private final OutboxRepository outbox;
  private final ConfirmedRabbitPublisher publisher;
  private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties;
  private final OutboxBackoffPolicy backoff;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Counter published;
  private final Counter retried;
  private final Counter failed;
  private final Counter claimLost;

  public OutboxRelay(
      OutboxRepository outbox,
      ConfirmedRabbitPublisher publisher,
      EnvelopeJsonCodec codec,
      MessagingProperties properties,
      OutboxBackoffPolicy backoff,
      PlatformTransactionManager transactionManager,
      Clock authorizationClock,
      MeterRegistry meterRegistry) {
    this.outbox = outbox;
    this.publisher = publisher;
    this.codec = codec;
    this.properties = properties;
    this.backoff = backoff;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = authorizationClock;
    this.published = Counter.builder("authorization.messaging.outbox.published").register(meterRegistry);
    this.retried = Counter.builder("authorization.messaging.outbox.retried").register(meterRegistry);
    this.failed = Counter.builder("authorization.messaging.outbox.failed").register(meterRegistry);
    this.claimLost = Counter.builder("authorization.messaging.outbox.claim_lost").register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.outbox-poll-interval:1s}")
  public void scheduledRelay() {
    if (properties.outboxRelayEnabled()) {
      relay();
    }
  }

  public void relay() {
    UUID claimToken = UUID.randomUUID();
    Instant now = clock.instant();
    List<OutboxMessage> claimed = transactions.execute(status ->
        outbox.claimBatch(
            properties.outboxClaimBatchSize(), claimToken, now.plus(properties.outboxLease()), now));
    if (claimed == null) {
      return;
    }
    for (OutboxMessage message : claimed) {
      relayOne(message, claimToken);
    }
  }

  private void relayOne(OutboxMessage message, UUID claimToken) {
    try {
      publish(message);
      Boolean marked = transactions.execute(status ->
          outbox.markPublished(message.messageId(), claimToken, clock.instant()));
      if (Boolean.TRUE.equals(marked)) {
        published.increment();
      } else {
        claimLost.increment();
      }
    } catch (PermanentOutboxException exception) {
      transitionFailed(message, claimToken, PublishFailureCode.OUTBOX_CONTRACT_INVALID, exception.getMessage());
    } catch (RabbitPublishException exception) {
      transitionRetry(message, claimToken, exception.code(), exception.getMessage());
    } catch (RuntimeException exception) {
      transitionRetry(message, claimToken, PublishFailureCode.PUBLISH_UNEXPECTED_ERROR, exception.getMessage());
    }
  }

  private void publish(OutboxMessage message) {
    MessageKind kind;
    try {
      kind = MessageKind.valueOf(message.messageKind());
    } catch (IllegalArgumentException exception) {
      throw new PermanentOutboxException("unsupported Outbox message kind", exception);
    }
    String exchange = switch (kind) {
      case EVENT -> MessagingNames.EVENTS_EXCHANGE;
      case RESULT -> MessagingNames.RESULTS_EXCHANGE;
      case QUERY -> MessagingNames.QUERIES_EXCHANGE;
      case COMMAND -> throw new PermanentOutboxException("Authorization never emits Commands", null);
    };
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(message.messageId().toString());
    Message amqpMessage = new Message(codec.encode(envelope(message, kind)), properties);
    if (kind == MessageKind.EVENT) {
      publisher.publishEvent(exchange, message.routingKey(), amqpMessage);
    } else {
      publisher.publishRequiredRoute(exchange, message.routingKey(), amqpMessage);
    }
  }

  private MessageEnvelope envelope(OutboxMessage message, MessageKind kind) {
    if (message.actor() == null) {
      throw new PermanentOutboxException("Outbox message lacks actor", null);
    }
    WireActor actor = new WireActor(
        message.actor().type().name(), message.actor().accountId(), message.actor().service());
    try {
      return new MessageEnvelope(
          message.messageId(),
          message.messageType(),
          message.messageVersion(),
          kind,
          message.producer(),
          message.occurredAt(),
          message.correlationId(),
          message.causationId(),
          message.operationId(),
          message.idempotencyKey(),
          message.aggregateId(),
          message.aggregateVersion(),
          actor,
          codec.readTree(message.payloadJson()));
    } catch (RuntimeException exception) {
      throw new PermanentOutboxException("invalid durable Outbox contract", exception);
    }
  }

  private void transitionRetry(
      OutboxMessage message, UUID claimToken, PublishFailureCode code, String detail) {
    int attempt = message.attempts() + 1;
    Boolean updated = transactions.execute(status -> outbox.releaseForRetry(
        message.messageId(),
        claimToken,
        clock.instant().plus(backoff.delayForAttempt(attempt)),
        code.name(),
        detail));
    if (Boolean.TRUE.equals(updated)) {
      retried.increment();
    } else {
      claimLost.increment();
    }
  }

  private void transitionFailed(
      OutboxMessage message, UUID claimToken, PublishFailureCode code, String detail) {
    Boolean updated = transactions.execute(status -> outbox.markFailed(
        message.messageId(), claimToken, code.name(), detail));
    if (Boolean.TRUE.equals(updated)) {
      failed.increment();
    } else {
      claimLost.increment();
    }
  }

  private static final class PermanentOutboxException extends RuntimeException {
    PermanentOutboxException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
