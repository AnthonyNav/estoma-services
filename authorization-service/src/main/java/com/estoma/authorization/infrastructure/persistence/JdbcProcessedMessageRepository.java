package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.ProcessedMessageRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProcessedMessageRepository implements ProcessedMessageRepository {

  private static final String COLUMNS =
      "consumer_name, message_id, status, message_type, message_version, message_kind, producer, "
          + "correlation_id, causation_id, operation_id, idempotency_key, aggregate_id, aggregate_version, "
          + "actor::text AS actor, payload::text AS payload, received_at, occurred_at, applied_at, deferred_reason, updated_at";
  private final JdbcClient jdbc;

  JdbcProcessedMessageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ProcessedMessage> find(String consumerName, UUID messageId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM processed_messages "
                + "WHERE consumer_name = :consumerName AND message_id = :messageId")
        .param("consumerName", consumerName)
        .param("messageId", messageId)
        .query(ProcessedMessageRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public boolean insertIfAbsent(ProcessedMessage message) {
    return jdbc.sql(
            """
            INSERT INTO processed_messages (
              consumer_name, message_id, status, message_type, message_version, message_kind, producer,
              correlation_id, causation_id, operation_id, idempotency_key, aggregate_id, aggregate_version,
              actor, payload, received_at, occurred_at, applied_at, deferred_reason, updated_at
            ) VALUES (
              :consumerName, :messageId, CAST(:status AS inbox_status), :messageType, :messageVersion, :messageKind, :producer,
              :correlationId, :causationId, :operationId, :idempotencyKey, :aggregateId, :aggregateVersion,
              CAST(:actor AS jsonb), CAST(:payload AS jsonb), :receivedAt, :occurredAt, :appliedAt, :deferredReason, :updatedAt
            ) ON CONFLICT (consumer_name, message_id) DO NOTHING
            """)
        .param("consumerName", message.consumerName())
        .param("messageId", message.messageId())
        .param("status", message.status())
        .param("messageType", message.messageType())
        .param("messageVersion", message.messageVersion())
        .param("messageKind", message.messageKind())
        .param("producer", message.producer())
        .param("correlationId", message.correlationId())
        .param("causationId", message.causationId())
        .param("operationId", message.operationId())
        .param("idempotencyKey", message.idempotencyKey())
        .param("aggregateId", message.aggregateId())
        .param("aggregateVersion", message.aggregateVersion())
        .param("actor", ActorJsonCodec.write(message.actor()))
        .param("payload", message.payloadJson())
        .param("receivedAt", Timestamps.toTimestamp(message.receivedAt()))
        .param("occurredAt", Timestamps.toTimestamp(message.occurredAt()))
        .param("appliedAt", Timestamps.toTimestamp(message.appliedAt()))
        .param("deferredReason", message.deferredReason())
        .param("updatedAt", Timestamps.toTimestamp(message.updatedAt()))
        .update()
        == 1;
  }

  @Override
  public int markApplied(String consumerName, UUID messageId, Instant appliedAt, Instant updatedAt) {
    return jdbc.sql(
            "UPDATE processed_messages SET status = 'APPLIED', applied_at = :appliedAt, "
                + "deferred_reason = NULL, updated_at = :updatedAt "
                + "WHERE consumer_name = :consumerName AND message_id = :messageId AND status = 'DEFERRED'")
        .param("consumerName", consumerName)
        .param("messageId", messageId)
        .param("appliedAt", Timestamps.toTimestamp(appliedAt))
        .param("updatedAt", Timestamps.toTimestamp(updatedAt))
        .update();
  }

  @Override
  public int markDeferred(String consumerName, UUID messageId, String deferredReason, Instant updatedAt) {
    return jdbc.sql(
            "UPDATE processed_messages SET status = 'DEFERRED', applied_at = NULL, "
                + "deferred_reason = :deferredReason, updated_at = :updatedAt "
                + "WHERE consumer_name = :consumerName AND message_id = :messageId")
        .param("consumerName", consumerName)
        .param("messageId", messageId)
        .param("deferredReason", deferredReason)
        .param("updatedAt", Timestamps.toTimestamp(updatedAt))
        .update();
  }

  @Override
  public boolean hasDeferredNewerThan(String consumerName, UUID aggregateId, int aggregateVersion) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM processed_messages "
                + "WHERE consumer_name = :consumerName AND status = 'DEFERRED' "
                + "AND aggregate_id = :aggregateId AND aggregate_version > :aggregateVersion)")
        .param("consumerName", consumerName)
        .param("aggregateId", aggregateId)
        .param("aggregateVersion", aggregateVersion)
        .query(Boolean.class)
        .single();
  }
}
