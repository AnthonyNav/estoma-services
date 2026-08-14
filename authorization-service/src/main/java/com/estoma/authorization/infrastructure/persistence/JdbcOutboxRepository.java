package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.OutboxRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxRepository implements OutboxRepository {

  private final JdbcClient jdbc;

  JdbcOutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(OutboxMessage message) {
    jdbc.sql(
            """
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind, producer, routing_key,
              operation_id, idempotency_key, actor, aggregate_id, aggregate_version,
              correlation_id, causation_id, payload, status, attempts, occurred_at, available_at,
              published_at, last_error_code, last_error_detail, claim_token, claimed_at, lease_until
            ) VALUES (
              :messageId, :messageType, :messageVersion, :messageKind, :producer, :routingKey,
              :operationId, :idempotencyKey, CAST(:actor AS jsonb), :aggregateId, :aggregateVersion,
              :correlationId, :causationId, CAST(:payload AS jsonb), CAST(:status AS outbox_status), :attempts,
              :occurredAt, :availableAt, :publishedAt, :lastErrorCode, :lastErrorDetail,
              :claimToken, :claimedAt, :leaseUntil
            )
            """)
        .param("messageId", message.messageId())
        .param("messageType", message.messageType())
        .param("messageVersion", message.messageVersion())
        .param("messageKind", message.messageKind())
        .param("producer", message.producer())
        .param("routingKey", message.routingKey())
        .param("operationId", message.operationId())
        .param("idempotencyKey", message.idempotencyKey())
        .param("actor", ActorJsonCodec.write(message.actor()))
        .param("aggregateId", message.aggregateId())
        .param("aggregateVersion", message.aggregateVersion())
        .param("correlationId", message.correlationId())
        .param("causationId", message.causationId())
        .param("payload", message.payloadJson())
        .param("status", message.status())
        .param("attempts", message.attempts())
        .param("occurredAt", Timestamps.toTimestamp(message.occurredAt()))
        .param("availableAt", Timestamps.toTimestamp(message.availableAt()))
        .param("publishedAt", Timestamps.toTimestamp(message.publishedAt()))
        .param("lastErrorCode", message.lastErrorCode())
        .param("lastErrorDetail", message.lastErrorDetail())
        .param("claimToken", message.claimToken())
        .param("claimedAt", Timestamps.toTimestamp(message.claimedAt()))
        .param("leaseUntil", Timestamps.toTimestamp(message.leaseUntil()))
        .update();
  }

  @Override
  public Optional<OutboxMessage> findById(UUID messageId) {
    return jdbc.sql(
            "SELECT message_id, message_type, message_version, message_kind, producer, routing_key, "
                + "operation_id, idempotency_key, actor::text AS actor, aggregate_id, aggregate_version, "
                + "correlation_id, causation_id, payload::text AS payload, status, attempts, occurred_at, available_at, "
                + "published_at, last_error_code, last_error_detail, claim_token, claimed_at, lease_until "
                + "FROM outbox_messages WHERE message_id = :messageId")
        .param("messageId", messageId)
        .query(OutboxRowMapper.INSTANCE)
        .optional();
  }
}
