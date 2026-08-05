package com.estoma.users.messaging.outbound;

import com.estoma.users.config.OutboxProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.OutboxMetadata;
import com.estoma.users.observability.OutboxMetrics;
import com.estoma.users.persistence.JdbcOutboxRepository;
import com.estoma.users.persistence.OutboxRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes claimed outbox rows to RabbitMQ, one at a time, each with its
 * own confirmed-publish outcome and its own fenced claim transition
 * (docs/system-contract.md §8). The claim already committed before this
 * method runs (inside {@code JdbcOutboxRepository#claimBatch}), the
 * publish here happens with no database transaction open, and marking
 * published/retried/failed is a second, separate, per-row write.
 *
 * <p>{@code metrics.published()} only increments once RabbitMQ confirmed
 * the publication AND the fenced {@code markPublished} transition
 * actually updated the row — a worker that lost its claim never gets to
 * increment it, and never gets to persist an error either (see {@link
 * #handleClaimLost}).
 *
 * <p>"users-service" is always the publisher of record — the {@code
 * producer} field on every RESULT/EVENT this service emits.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final String PRODUCER = "users-service";

  private final JdbcOutboxRepository outboxRepository;
  private final RoutingKeyResolver routingKeyResolver;
  private final ConfirmedRabbitPublisher publisher;
  private final EnvelopeJsonCodec codec;
  private final OutboxProperties properties;
  private final OutboxBackoffPolicy backoffPolicy;
  private final OutboxMetrics metrics;
  private final Clock clock;

  public OutboxRelay(
      JdbcOutboxRepository outboxRepository,
      RoutingKeyResolver routingKeyResolver,
      ConfirmedRabbitPublisher publisher,
      EnvelopeJsonCodec codec,
      OutboxProperties properties,
      OutboxBackoffPolicy backoffPolicy,
      OutboxMetrics metrics,
      Clock clock) {
    this.outboxRepository = outboxRepository;
    this.routingKeyResolver = routingKeyResolver;
    this.publisher = publisher;
    this.codec = codec;
    this.properties = properties;
    this.backoffPolicy = backoffPolicy;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 2_000)
  public void relay() {
    List<OutboxRecord> claimed = outboxRepository.claimBatch(properties.claimBatchSize(), properties.lease());
    if (claimed.isEmpty()) {
      return;
    }

    for (OutboxRecord record : claimed) {
      try {
        publish(record);
      } catch (RabbitPublishException e) {
        handleFailure(record, e.code());
        if (e.code() == OutboxFailureCode.PUBLISH_INTERRUPTED) {
          // ConfirmedRabbitPublisher already restored the flag the
          // instant it caught InterruptedException; setting it again here
          // is idempotent and makes this the one place that decides what
          // happens next: no sleep, no continuing on an interrupted
          // thread. The remaining claimed rows stay PROCESSING and the
          // sweeper reclaims them once their lease expires
          // (docs/system-contract.md §7, Round 1).
          Thread.currentThread().interrupt();
          return;
        }
        continue;
      } catch (Exception e) {
        handleFailure(record, OutboxFailureCode.PUBLISH_UNEXPECTED_ERROR);
        continue;
      }

      int rows = outboxRepository.markPublished(record.messageId(), record.claimToken(), clock.instant());
      if (rows == 1) {
        metrics.published();
      } else {
        handleClaimLost(record, "markPublished");
      }
    }
  }

  private void publish(OutboxRecord record) {
    MessageKind kind = MessageKind.valueOf(record.messageKind());
    OutboxMetadata metadata =
        record.metadataJson() == null ? null : codec.readValue(record.metadataJson(), OutboxMetadata.class);

    RoutingKeyResolver.Destination destination =
        switch (kind) {
          case EVENT -> routingKeyResolver.resolveEvent(record.messageType());
          case RESULT -> routingKeyResolver.resolveResult(metadata == null ? null : metadata.destination());
          case COMMAND -> throw new IllegalStateException("users-service never publishes COMMAND messages");
        };

    MessageEnvelope envelope = buildEnvelope(record, kind, metadata);
    byte[] body = codec.encode(envelope);

    MessageProperties amqpProps = new MessageProperties();
    amqpProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    amqpProps.setMessageId(record.messageId().toString());
    Message message = new Message(body, amqpProps);

    switch (kind) {
      case RESULT -> publisher.publishRequiredRoute(destination.exchange(), destination.routingKey(), message);
      case EVENT -> publisher.publishEvent(destination.exchange(), destination.routingKey(), message);
      case COMMAND -> throw new IllegalStateException("users-service never publishes COMMAND messages");
    }
  }

  /** A publish attempt failed to be confirmed; release for retry or fail the row, fenced by its claim token. */
  private void handleFailure(OutboxRecord record, OutboxFailureCode failureCode) {
    metrics.failedPublish();
    Instant now = clock.instant();
    int rows =
        record.attempts() >= properties.maxAttempts()
            ? outboxRepository.markFailed(record.messageId(), record.claimToken(), failureCode)
            : outboxRepository.releaseForRetry(
                record.messageId(),
                record.claimToken(),
                now.plus(backoffPolicy.delayForAttempt(record.attempts())),
                failureCode);
    if (rows == 0) {
      handleClaimLost(record, "handleFailure(" + failureCode + ")");
    }
  }

  /**
   * The fenced transition affected zero rows: another worker already
   * reclaimed this row's lease. This worker has no right to write
   * anything onto the row anymore -- log and count it, but never persist
   * a code (docs/system-contract.md §8, §10).
   */
  private void handleClaimLost(OutboxRecord record, String duringOperation) {
    log.warn(
        "CLAIM_LOST_BEFORE_MARK: messageId={} claimToken={} during={}",
        record.messageId(),
        record.claimToken(),
        duringOperation);
    metrics.claimLost();
  }

  private MessageEnvelope buildEnvelope(OutboxRecord record, MessageKind kind, OutboxMetadata metadata) {
    var actorAccountId = metadata == null ? null : metadata.actorAccountId();
    var operationId = kind == MessageKind.RESULT && metadata != null ? metadata.operationId() : null;

    return new MessageEnvelope(
        record.messageId(),
        record.messageType(),
        record.messageVersion(),
        kind,
        PRODUCER,
        record.occurredAt(),
        record.correlationId(),
        record.causationId(),
        operationId,
        null, // targetService only applies to COMMAND messages
        record.aggregateId(),
        record.aggregateVersion(),
        actorAccountId == null ? null : new Actor(actorAccountId),
        codec.parseJson(record.payloadJson()));
  }
}
