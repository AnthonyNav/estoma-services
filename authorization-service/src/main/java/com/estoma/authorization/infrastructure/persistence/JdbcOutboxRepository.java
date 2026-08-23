package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.OutboxRepository;
import java.time.Instant;
import java.util.List;
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

  @Override
  public List<OutboxMessage> claimBatch(int limit, UUID claimToken, Instant leaseUntil, Instant now) {
    if (limit <= 0) {
      throw new IllegalArgumentException("claim limit must be positive");
    }
    reclaimExpiredLeases(now);
    return jdbc.sql(
            """
            WITH candidates AS (
              SELECT message_id
              FROM outbox_messages
              WHERE status = CAST('PENDING' AS outbox_status)
                AND available_at <= :now
              ORDER BY occurred_at, message_id
              LIMIT :limit
              FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_messages row
            SET status = CAST('PROCESSING' AS outbox_status),
                claim_token = :claimToken,
                claimed_at = :now,
                lease_until = :leaseUntil
            FROM candidates
            WHERE row.message_id = candidates.message_id
            RETURNING
              row.message_id, row.message_type, row.message_version, row.message_kind,
              row.producer, row.routing_key, row.operation_id, row.idempotency_key,
              row.actor::text AS actor, row.aggregate_id, row.aggregate_version,
              row.correlation_id, row.causation_id, row.payload::text AS payload,
              row.status, row.attempts, row.occurred_at, row.available_at,
              row.published_at, row.last_error_code, row.last_error_detail,
              row.claim_token, row.claimed_at, row.lease_until
            """)
        .param("limit", limit)
        .param("claimToken", claimToken)
        .param("now", Timestamps.toTimestamp(now))
        .param("leaseUntil", Timestamps.toTimestamp(leaseUntil))
        .query(OutboxRowMapper.INSTANCE)
        .list();
  }

  @Override
  public boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
            SET status = CAST('PUBLISHED' AS outbox_status),
                published_at = :publishedAt,
                claim_token = NULL,
                claimed_at = NULL,
                lease_until = NULL,
                last_error_code = NULL,
                last_error_detail = NULL
            WHERE message_id = :messageId
              AND status = CAST('PROCESSING' AS outbox_status)
              AND claim_token = :claimToken
            """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("publishedAt", Timestamps.toTimestamp(publishedAt))
        .update() == 1;
  }

  @Override
  public boolean releaseForRetry(
      UUID messageId,
      UUID claimToken,
      Instant availableAt,
      String errorCode,
      String errorDetail) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
            SET status = CAST('PENDING' AS outbox_status),
                attempts = attempts + 1,
                available_at = :availableAt,
                last_error_code = :errorCode,
                last_error_detail = :errorDetail,
                claim_token = NULL,
                claimed_at = NULL,
                lease_until = NULL
            WHERE message_id = :messageId
              AND status = CAST('PROCESSING' AS outbox_status)
              AND claim_token = :claimToken
            """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("availableAt", Timestamps.toTimestamp(availableAt))
        .param("errorCode", errorCode)
        .param("errorDetail", truncate(errorDetail))
        .update() == 1;
  }

  @Override
  public boolean markFailed(UUID messageId, UUID claimToken, String errorCode, String errorDetail) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
            SET status = CAST('FAILED' AS outbox_status),
                attempts = attempts + 1,
                last_error_code = :errorCode,
                last_error_detail = :errorDetail,
                claim_token = NULL,
                claimed_at = NULL,
                lease_until = NULL
            WHERE message_id = :messageId
              AND status = CAST('PROCESSING' AS outbox_status)
              AND claim_token = :claimToken
            """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("errorCode", errorCode)
        .param("errorDetail", truncate(errorDetail))
        .update() == 1;
  }

  @Override
  public int reclaimExpiredLeases(Instant now) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
            SET status = CAST('PENDING' AS outbox_status),
                available_at = :now,
                last_error_code = 'LEASE_EXPIRED',
                last_error_detail = NULL,
                claim_token = NULL,
                claimed_at = NULL,
                lease_until = NULL
            WHERE status = CAST('PROCESSING' AS outbox_status)
              AND lease_until < :now
            """)
        .param("now", Timestamps.toTimestamp(now))
        .update();
  }

  @Override
  public Instant oldestPendingAvailableAt() {
    return jdbc.sql(
            "SELECT available_at FROM outbox_messages "
                + "WHERE status = CAST('PENDING' AS outbox_status) "
                + "ORDER BY available_at, occurred_at LIMIT 1")
        .query((rs, rowNum) -> Timestamps.toInstant(rs, "available_at"))
        .optional()
        .orElse(null);
  }

  @Override
  public boolean hasFailedMessages() {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM outbox_messages "
                + "WHERE status = CAST('FAILED' AS outbox_status))")
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean hasExpiredProcessing(Instant now) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM outbox_messages "
                + "WHERE status = CAST('PROCESSING' AS outbox_status) AND lease_until < :now)")
        .param("now", Timestamps.toTimestamp(now))
        .query(Boolean.class)
        .single();
  }

  private static String truncate(String value) {
    if (value == null || value.length() <= 1000) {
      return value;
    }
    return value.substring(0, 1000);
  }
}
