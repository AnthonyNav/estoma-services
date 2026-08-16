package com.estoma.washadministration.infrastructure.messaging.outbound;

import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.OutboxRepository.OutboxRecord;
import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessageKind;
import com.estoma.washadministration.contracts.MessagingNames;
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
  private final MessagingProperties properties;
  private final OutboxBackoffPolicy backoff;
  private final TransactionTemplate transaction;

  public OutboxRelay(
      OutboxRepository outbox,
      RoutingKeyResolver routingKeys,
      ConfirmedRabbitPublisher publisher,
      EnvelopeJsonCodec codec,
      MessagingProperties properties,
      OutboxBackoffPolicy backoff,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
    this.outbox = outbox;
    this.routingKeys = routingKeys;
    this.publisher = publisher;
    this.codec = codec;
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
        handleFailure(record, claimToken, exception.code().name());
      } catch (Exception exception) {
        handleFailure(record, claimToken, OutboxFailureCode.PUBLISH_UNEXPECTED_ERROR.name());
      }
    }
  }

  private void publish(OutboxRecord record) {
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
            codec.readTree(record.payloadJson()));
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(record.messageId().toString());
    Message message = new Message(codec.encode(envelope), properties);
    if (kind == MessageKind.EVENT) {
      publisher.publishEvent(destination.exchange(), destination.routingKey(), message);
    } else if (kind == MessageKind.RESULT || kind == MessageKind.QUERY) {
      publisher.publishRequiredRoute(destination.exchange(), destination.routingKey(), message);
    } else {
      throw new IllegalArgumentException("unsupported Outbox message kind: " + kind);
    }
  }

  private void handleFailure(OutboxRecord record, UUID claimToken, String errorCode) {
    Boolean updated =
        transaction.execute(
            status -> {
              int attemptsAfterFailure = record.attempts() + 1;
              if (attemptsAfterFailure >= properties.outboxMaxAttempts()) {
                return outbox.markFailed(record.messageId(), claimToken, errorCode);
              }
              return outbox.releaseForRetry(
                  record.messageId(),
                  claimToken,
                  Instant.now().plus(backoff.delayForAttempt(attemptsAfterFailure)),
                  errorCode);
            });
    if (!Boolean.TRUE.equals(updated)) {
      throw new IllegalStateException("Outbox failure fencing transition failed");
    }
  }
}
