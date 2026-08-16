package com.estoma.washadministration.infrastructure.messaging.outbound;

import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.OutboxRepository.OutboxRecord;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessageKind;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washadministration.infrastructure.messaging.MessagingProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component("outboxRelayService")
public class OutboxRelay {

  private final OutboxRepository outbox;
  private final RoutingKeyResolver routingKeys;
  private final ConfirmedRabbitPublisher publisher;
  private final EnvelopeJsonCodec codec;
  private final OutboundWirePayloadMapper wirePayloads;
  private final MessagingProperties properties;
  private final OutboxBackoffPolicy backoff;
  private final TransactionTemplate transaction;

  public OutboxRelay(
      OutboxRepository outbox,
      RoutingKeyResolver routingKeys,
      ConfirmedRabbitPublisher publisher,
      EnvelopeJsonCodec codec,
      OutboundWirePayloadMapper wirePayloads,
      MessagingProperties properties,
      OutboxBackoffPolicy backoff,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
    this.outbox = outbox;
    this.routingKeys = routingKeys;
    this.publisher = publisher;
    this.codec = codec;
    this.wirePayloads = wirePayloads;
    this.properties = properties;
    this.backoff = backoff;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.outbox-poll-interval:1000}")
  public void scheduledRelay() {
    if (properties.outboxRelayEnabled()) {
      relay();
    }
  }

  public void relay() {
    UUID claimToken = UUID.randomUUID();
    Instant leaseUntil = Instant.now().plus(properties.publisherConfirmTimeout().multipliedBy(
        Math.max(1, properties.outboxClaimBatchSize())).plusSeconds(30));
    List<OutboxRecord> claimed =
        transaction.execute(
            status ->
                outbox.claimBatch(
                    properties.outboxClaimBatchSize(), claimToken, leaseUntil));
    if (claimed == null) {
      return;
    }
    for (OutboxRecord record : claimed) {
      try {
        publish(record);
        Boolean markedPublished =
            transaction.execute(
                status -> outbox.markPublished(record.messageId(), claimToken, Instant.now()));
        if (!Boolean.TRUE.equals(markedPublished)) {
          throw new IllegalStateException("Outbox publish fencing transition failed");
        }
      } catch (RabbitPublishException exception) {
        handleTransientFailure(record, claimToken, exception.code().name());
      } catch (PermanentOutboxException exception) {
        handlePermanentFailure(record, claimToken, OutboxFailureCode.OUTBOX_CONTRACT_INVALID.name());
      } catch (Exception exception) {
        // Unknown failures preserve the durable publication obligation. Operators can observe age/attempts
        // and explicitly quarantine a row if it is proven permanently non-publishable.
        handleTransientFailure(record, claimToken, OutboxFailureCode.PUBLISH_UNEXPECTED_ERROR.name());
      }
    }
  }

  private void publish(OutboxRecord record) {
    PreparedMessage prepared = prepare(record);
    if (prepared.kind() == MessageKind.EVENT) {
      publisher.publishEvent(
          prepared.destination().exchange(), prepared.destination().routingKey(), prepared.message());
    } else if (prepared.kind() == MessageKind.RESULT || prepared.kind() == MessageKind.QUERY) {
      publisher.publishRequiredRoute(
          prepared.destination().exchange(), prepared.destination().routingKey(), prepared.message());
    } else {
      throw new PermanentOutboxException("unsupported Outbox message kind: " + prepared.kind(), null);
    }
  }

  private PreparedMessage prepare(OutboxRecord record) {
    try {
      MessageKind kind = MessageKind.valueOf(record.messageKind());
      RoutingKeyResolver.Destination destination =
          routingKeys.resolve(kind, record.messageType(), record.logicalDestination());
      MessageEnvelope envelope =
          new MessageEnvelope(
              record.messageId(),
              record.messageType(),
              record.messageVersion(),
              kind,
              record.producer(),
              record.occurredAt(),
              record.correlationId(),
              record.causationId(),
              record.operationId(),
              record.idempotencyKey(),
              record.aggregateId(),
              record.aggregateVersion(),
              codec.readActor(record.actorJson()),
              wirePayloads.map(kind, record.messageType(), record.payloadJson()));
      MessageProperties messageProperties = new MessageProperties();
      messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
      messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      messageProperties.setMessageId(record.messageId().toString());
      return new PreparedMessage(
          kind,
          destination,
          new Message(codec.encode(envelope), messageProperties));
    } catch (PermanentOutboxException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new PermanentOutboxException("invalid durable Outbox contract", exception);
    }
  }

  private void handleTransientFailure(OutboxRecord record, UUID claimToken, String errorCode) {
    int attemptsAfterFailure = record.attempts() + 1;
    Boolean updated =
        transaction.execute(
            status ->
                outbox.releaseForRetry(
                    record.messageId(),
                    claimToken,
                    Instant.now().plus(backoff.delayForAttempt(attemptsAfterFailure)),
                    errorCode));
    if (!Boolean.TRUE.equals(updated)) {
      throw new IllegalStateException("Outbox retry fencing transition failed");
    }
  }

  private void handlePermanentFailure(OutboxRecord record, UUID claimToken, String errorCode) {
    Boolean updated =
        transaction.execute(
            status -> outbox.markFailed(record.messageId(), claimToken, errorCode));
    if (!Boolean.TRUE.equals(updated)) {
      throw new IllegalStateException("Outbox permanent failure fencing transition failed");
    }
  }

  private record PreparedMessage(
      MessageKind kind,
      RoutingKeyResolver.Destination destination,
      Message message) {}

  private static final class PermanentOutboxException extends RuntimeException {
    PermanentOutboxException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
