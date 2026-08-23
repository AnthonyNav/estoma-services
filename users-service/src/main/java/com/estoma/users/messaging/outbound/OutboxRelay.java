package com.estoma.users.messaging.outbound;

import com.estoma.users.config.OutboxProperties;
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

@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final String PRODUCER = "users";

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
    OutboxMetadata metadata = record.metadataJson() == null ? null : codec.readValue(record.metadataJson(), OutboxMetadata.class);
    RoutingKeyResolver.Destination destination = switch (kind) {
      case EVENT -> routingKeyResolver.resolveEvent(record.messageType());
      case RESULT -> routingKeyResolver.resolveResult(metadata == null ? null : metadata.destination());
      case COMMAND -> throw new IllegalStateException("users-service never publishes COMMAND messages");
      case QUERY -> throw new IllegalStateException("users-service never publishes QUERY messages");
    };
    MessageEnvelope envelope = buildEnvelope(record, kind, metadata);
    MessageProperties amqpProps = new MessageProperties();
    amqpProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    amqpProps.setMessageId(record.messageId().toString());
    Message message = new Message(codec.encode(envelope), amqpProps);
    switch (kind) {
      case RESULT -> publisher.publishRequiredRoute(destination.exchange(), destination.routingKey(), message);
      case EVENT -> publisher.publishEvent(destination.exchange(), destination.routingKey(), message);
      case COMMAND -> throw new IllegalStateException("users-service never publishes COMMAND messages");
      case QUERY -> throw new IllegalStateException("users-service never publishes QUERY messages");
    }
  }

  private void handleFailure(OutboxRecord record, OutboxFailureCode failureCode) {
    metrics.failedPublish();
    Instant now = clock.instant();
    int rows = record.attempts() >= properties.maxAttempts()
        ? outboxRepository.markFailed(record.messageId(), record.claimToken(), failureCode)
        : outboxRepository.releaseForRetry(record.messageId(), record.claimToken(),
            now.plus(backoffPolicy.delayForAttempt(record.attempts())), failureCode);
    if (rows == 0) {
      handleClaimLost(record, "handleFailure(" + failureCode + ")");
    }
  }

  private void handleClaimLost(OutboxRecord record, String duringOperation) {
    log.warn("CLAIM_LOST_BEFORE_MARK: messageId={} claimToken={} during={}",
        record.messageId(), record.claimToken(), duringOperation);
    metrics.claimLost();
  }

  private MessageEnvelope buildEnvelope(OutboxRecord record, MessageKind kind, OutboxMetadata metadata) {
    var actor = metadata == null ? null : metadata.actor();
    var operationId = kind == MessageKind.RESULT && metadata != null ? metadata.operationId() : null;
    var idempotencyKey = kind == MessageKind.RESULT && metadata != null ? metadata.idempotencyKey() : null;
    return new MessageEnvelope(
        record.messageId(), record.messageType(), record.messageVersion(), kind, PRODUCER,
        record.occurredAt(), record.correlationId(), record.causationId(), operationId,
        idempotencyKey, record.aggregateId(), record.aggregateVersion(), actor,
        codec.parseJson(record.payloadJson()));
  }
}
