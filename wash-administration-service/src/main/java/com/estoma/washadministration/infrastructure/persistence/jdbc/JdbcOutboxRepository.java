package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.OutboxRepository.OutboxRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

  private final JdbcClient jdbc;

  public JdbcOutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(OutboxMessage message) {
    jdbc.sql(
            """
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind, producer, occurred_at,
              routing_key,
              operation_id, idempotency_key, aggregate_id, aggregate_version, correlation_id,
              causation_id, actor, payload, metadata, status, attempts, available_at
            ) VALUES (
              :messageId, :messageType, :messageVersion, :messageKind, :producer, :occurredAt,
              :logicalDestination,
              :operationId, :idempotencyKey, :aggregateId, :aggregateVersion, :correlationId,
              :causationId, CAST(:actor AS jsonb), CAST(:payload AS jsonb), CAST(:metadata AS jsonb),
              'PENDING', 0, :availableAt
            )
            """)
        .param("messageId", message.messageId())
        .param("messageType", message.messageType())
        .param("messageVersion", message.messageVersion())
        .param("messageKind", message.messageKind())
        .param("producer", message.producer())
        // PR1 created the physical column as routing_key. Until PR3 owns transport mapping,
        // it stores only the transport-neutral logical destination token from Application.
        .param("logicalDestination", message.logicalDestination())
        .param("occurredAt", Timestamps.toTimestamp(message.occurredAt()))
        .param("operationId", message.operationId())
        .param("idempotencyKey", message.idempotencyKey())
        .param("aggregateId", message.aggregateId())
        .param("aggregateVersion", message.aggregateVersion())
        .param("correlationId", message.correlationId())
        .param("causationId", message.causationId())
        .param("actor", message.actorJson())
        .param("payload", message.payloadJson())
        .param("metadata", message.metadataJson())
        .param("availableAt", Timestamps.toTimestamp(message.occurredAt()))
        .update();
  }

  @Override
  public List<OutboxRecord> claimBatch(int limit, UUID claimToken, Instant leaseUntil) {
    jdbc.sql(
            """
            UPDATE outbox_messages
            SET status = 'PENDING', claim_token = NULL, lease_until = NULL
            WHERE status = 'PROCESSING' AND lease_until < now()
            """)
        .update();

    List<UUID> ids =
        jdbc.sql(
                """
                SELECT message_id
                FROM outbox_messages
                WHERE status = 'PENDING' AND available_at <= now()
                ORDER BY occurred_at, message_id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
            .param("limit", limit)
            .query((rs, rowNum) -> rs.getObject("message_id", UUID.class))
            .list();
    if (ids.isEmpty()) {
      return List.of();
    }
    for (UUID id : ids) {
      jdbc.sql(
              """
              UPDATE outbox_messages
              SET status = 'PROCESSING', claim_token = :claimToken, lease_until = :leaseUntil
              WHERE message_id = :messageId AND status = 'PENDING'
              """)
          .param("claimToken", claimToken)
          .param("leaseUntil", Timestamps.toTimestamp(leaseUntil))
          .param("messageId", id)
          .update();
    }
    return jdbc.sql(
            """
            SELECT message_id, message_type, message_version, message_kind, producer, routing_key,
                   occurred_at, operation_id, idempotency_key, aggregate_id, aggregate_version,
                   correlation_id, causation_id, actor::text AS actor, payload::text AS payload,
                   metadata::text AS metadata, attempts, claim_token
            FROM outbox_messages
            WHERE claim_token = :claimToken AND status = 'PROCESSING'
            ORDER BY occurred_at, message_id
            """)
        .param("claimToken", claimToken)
        .query(
            (rs, rowNum) ->
                new OutboxRecord(
                    rs.getObject("message_id", UUID.class),
                    rs.getString("message_type"),
                    rs.getInt("message_version"),
                    rs.getString("message_kind"),
                    rs.getString("producer"),
                    rs.getString("routing_key"),
                    Timestamps.toInstant(rs, "occurred_at"),
                    rs.getObject("operation_id", UUID.class),
                    rs.getString("idempotency_key"),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getObject("aggregate_version") == null ? null : rs.getInt("aggregate_version"),
                    rs.getObject("correlation_id", UUID.class),
                    rs.getObject("causation_id", UUID.class),
                    rs.getString("actor"),
                    rs.getString("payload"),
                    rs.getString("metadata"),
                    rs.getInt("attempts"),
                    rs.getObject("claim_token", UUID.class)))
        .list();
  }

  @Override
  public boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
            SET status = 'PUBLISHED', published_at = :publishedAt, claim_token = NULL, lease_until = NULL
            WHERE message_id = :messageId AND status = 'PROCESSING' AND claim_token = :claimToken
            """)
        .param("publishedAt", Timestamps.toTimestamp(publishedAt))
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .update()
        == 1;
  }

  @Override
  public boolean releaseForRetry(UUID messageId, UUID claimToken, Instant availableAt, String errorCode) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
            SET status = 'PENDING', attempts = attempts + 1, available_at = :availableAt,
                last_error_code = :errorCode, last_error_at = now(), claim_token = NULL, lease_until = NULL
            WHERE message_id = :messageId AND status = 'PROCESSING' AND claim_token = :claimToken
            """)
        .param("availableAt", Timestamps.toTimestamp(availableAt))
        .param("errorCode", errorCode)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .update()
        == 1;
  }

  @Override
  public boolean markFailed(UUID messageId, UUID claimToken, String errorCode) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
            SET status = 'FAILED', attempts = attempts + 1, last_error_code = :errorCode,
                last_error_at = now(), claim_token = NULL, lease_until = NULL
            WHERE message_id = :messageId AND status = 'PROCESSING' AND claim_token = :claimToken
            """)
        .param("errorCode", errorCode)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .update()
        == 1;
  }

  @Override
  public Instant oldestOverduePendingAvailableAt() {
    return jdbc.sql(
            "SELECT min(available_at) AS oldest FROM outbox_messages WHERE status = 'PENDING' AND available_at <= now()")
        .query((rs, rowNum) -> Timestamps.toInstant(rs, "oldest"))
        .optional()
        .orElse(null);
  }

  @Override
  public boolean hasFailedMessages() {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM outbox_messages WHERE status = 'FAILED')")
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean hasExpiredProcessing() {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM outbox_messages WHERE status = 'PROCESSING' AND lease_until < now())")
        .query(Boolean.class)
        .single();
  }
}
