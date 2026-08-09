package com.estoma.authentication.infrastructure.messaging.outbound;

import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.infrastructure.messaging.AmqpNames;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.messaging.MessagingProperties;
import com.estoma.authentication.infrastructure.persistence.JdbcOutboxRepository;
import com.estoma.authentication.infrastructure.persistence.OutboxRecord;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes claimed Outbox rows to RabbitMQ, per system_contract.md
 * "Outbox Operational Policy": claim up to 50 PENDING rows, publish each
 * with a publisher confirm, mark {@code PUBLISHED} on ack, back off and
 * retry on failure, {@code FAILED} after 10 attempts.
 *
 * <p>One {@code @Transactional} poll tick claims the batch and publishes/
 * marks every row in it. If the pod crashes after a publish is confirmed
 * but before the transaction commits, the whole tick rolls back and the
 * row is republished on the next tick -- the accepted at-least-once
 * duplication messaging-architecture.md §9 describes, not a bug. Single
 * process per pod (system_contract.md), so no cross-instance lease/fencing
 * is needed in this tramo.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final JdbcOutboxRepository outboxRepository;
  private final RoutingKeyResolver routingKeyResolver;
  private final ConfirmedRabbitPublisher publisher;
  private final EnvelopeJsonCodec codec;
  private final MessagingProperties properties;
  private final OutboxBackoffPolicy backoffPolicy;

  public OutboxRelay(
      JdbcOutboxRepository outboxRepository,
      RoutingKeyResolver routingKeyResolver,
      ConfirmedRabbitPublisher publisher,
      EnvelopeJsonCodec codec,
      MessagingProperties properties,
      OutboxBackoffPolicy backoffPolicy) {
    this.outboxRepository = outboxRepository;
    this.routingKeyResolver = routingKeyResolver;
    this.publisher = publisher;
    this.codec = codec;
    this.properties = properties;
    this.backoffPolicy = backoffPolicy;
  }

  /**
   * The scheduled entry point, gated by {@code
   * estoma.messaging.outbox-relay-enabled}. {@link #relay} itself stays
   * ungated so integration tests can invoke it directly and
   * deterministically, without racing a background tick.
   */
  @Scheduled(fixedDelayString = "${estoma.messaging.outbox-poll-interval:1000}")
  public void scheduledRelay() {
    if (properties.outboxRelayEnabled()) {
      relay();
    }
  }

  @Transactional
  public void relay() {
    List<OutboxRecord> claimed = outboxRepository.claimBatch(properties.outboxClaimBatchSize());
    for (OutboxRecord record : claimed) {
      try {
        publish(record);
        outboxRepository.markPublished(record.messageId(), Instant.now());
      } catch (RabbitPublishException e) {
        handleFailure(record, e.code());
      } catch (Exception e) {
        log.error("unexpected Outbox publish failure; messageId={}", record.messageId(), e);
        handleFailure(record, OutboxFailureCode.PUBLISH_UNEXPECTED_ERROR);
      }
    }
  }

  private void publish(OutboxRecord record) {
    MessageKind kind = MessageKind.valueOf(record.messageKind());
    RoutingKeyResolver.Destination destination =
        switch (kind) {
          case EVENT -> routingKeyResolver.resolveEvent(record.messageType());
          case RESULT -> routingKeyResolver.resolveResult();
          case COMMAND -> throw new IllegalStateException("authentication-service never publishes COMMAND messages");
        };

    MessageEnvelope envelope = buildEnvelope(record, kind);
    byte[] body = codec.encode(envelope);

    MessageProperties amqpProps = new MessageProperties();
    amqpProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    amqpProps.setMessageId(record.messageId().toString());
    Message message = new Message(body, amqpProps);

    switch (kind) {
      case RESULT -> publisher.publishRequiredRoute(destination.exchange(), destination.routingKey(), message);
      case EVENT -> publisher.publishEvent(destination.exchange(), destination.routingKey(), message);
      case COMMAND -> throw new IllegalStateException("authentication-service never publishes COMMAND messages");
    }
  }

  private void handleFailure(OutboxRecord record, OutboxFailureCode failureCode) {
    int attemptsAfterThisFailure = record.attempts() + 1;
    if (attemptsAfterThisFailure >= properties.outboxMaxAttempts()) {
      outboxRepository.markFailed(record.messageId());
      log.error("Outbox row exhausted retries and is now FAILED; messageId={} errorCode={}", record.messageId(), failureCode);
      return;
    }
    Instant availableAt = Instant.now().plus(backoffPolicy.delayForAttempt(attemptsAfterThisFailure));
    outboxRepository.releaseForRetry(record.messageId(), availableAt);
  }

  private MessageEnvelope buildEnvelope(OutboxRecord record, MessageKind kind) {
    return new MessageEnvelope(
        record.messageId(),
        record.messageType(),
        record.messageVersion(),
        kind,
        AmqpNames.PRODUCER,
        record.occurredAt(),
        record.correlationId(),
        record.causationId(),
        record.operationId(),
        record.idempotencyKey(),
        record.aggregateId(),
        record.aggregateVersion(),
        record.actor(),
        codec.readTree(record.payloadJson()));
  }
}
