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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes claimed outbox rows to RabbitMQ. Not transactional — the
 * claim, the publish, and the mark are three separate steps
 * (docs/system-contract.md §8): claiming already committed before this
 * method runs (inside {@code JdbcOutboxRepository#claimBatch}), the
 * publish here happens with no database transaction open, and marking
 * published/retried is a second, separate write.
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
  private final RabbitTemplate rabbitTemplate;
  private final EnvelopeJsonCodec codec;
  private final OutboxProperties properties;
  private final OutboxMetrics metrics;

  public OutboxRelay(
      JdbcOutboxRepository outboxRepository,
      RoutingKeyResolver routingKeyResolver,
      RabbitTemplate rabbitTemplate,
      EnvelopeJsonCodec codec,
      OutboxProperties properties,
      OutboxMetrics metrics) {
    this.outboxRepository = outboxRepository;
    this.routingKeyResolver = routingKeyResolver;
    this.rabbitTemplate = rabbitTemplate;
    this.codec = codec;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelay = 2_000)
  public void relay() {
    List<OutboxRecord> claimed = outboxRepository.claimBatch(properties.claimBatchSize(), properties.lease());
    if (claimed.isEmpty()) {
      return;
    }

    List<UUID> published = new ArrayList<>();
    List<UUID> failed = new ArrayList<>();
    for (OutboxRecord record : claimed) {
      try {
        publish(record);
        published.add(record.messageId());
        metrics.published();
      } catch (Exception e) {
        log.error("failed to publish outbox message {} ({}): {}", record.messageId(), record.messageType(), e.toString());
        failed.add(record.messageId());
        metrics.failedPublish();
      }
    }

    if (!published.isEmpty()) {
      outboxRepository.markPublished(published);
    }
    if (!failed.isEmpty()) {
      outboxRepository.releaseForRetry(failed);
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

    rabbitTemplate.send(
        destination.exchange(), destination.routingKey(), message, new CorrelationData(record.messageId().toString()));
  }

  private MessageEnvelope buildEnvelope(OutboxRecord record, MessageKind kind, OutboxMetadata metadata) {
    UUID actorAccountId = metadata == null ? null : metadata.actorAccountId();
    UUID operationId = kind == MessageKind.RESULT && metadata != null ? metadata.operationId() : null;

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
