package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.OutboxRepository;
import com.estoma.platformbff.domain.messaging.OutboxMessage;
import com.estoma.platformbff.domain.messaging.OutboxStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {
  private static final Duration CLAIM_DURATION = Duration.ofSeconds(30);
  private final JdbcClient jdbc;

  public JdbcOutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(OutboxMessage message) {
    jdbc.sql("""
        INSERT INTO outbox_messages (
          message_id, message_kind, message_type, message_version, exchange_name,
          routing_key, operation_id, correlation_id, causation_id, idempotency_key,
          actor, payload, status, claim_token, claim_until, attempt_count,
          next_attempt_at, published_at, last_error_code, created_at, updated_at
        ) VALUES (:messageId, :messageKind, :messageType, :messageVersion, :exchangeName,
          :routingKey, :operationId, :correlationId, :causationId, :idempotencyKey,
          CAST(:actorJson AS jsonb), CAST(:payloadJson AS jsonb), :status, :claimToken,
          :claimUntil, :attemptCount, :nextAttemptAt, :publishedAt, :lastErrorCode,
          :createdAt, :updatedAt)
        """)
        .param("messageId", message.messageId())
        .param("messageKind", message.messageKind())
        .param("messageType", message.messageType())
        .param("messageVersion", message.messageVersion())
        .param("exchangeName", message.exchangeName())
        .param("routingKey", message.routingKey())
        .param("operationId", message.operationId())
        .param("correlationId", message.correlationId())
        .param("causationId", message.causationId())
        .param("idempotencyKey", message.idempotencyKey())
        .param("actorJson", message.actorJson())
        .param("payloadJson", message.payloadJson())
        .param("status", message.status().name())
        .param("claimToken", message.claimToken())
        .param("claimUntil", message.claimUntil())
        .param("attemptCount", message.attemptCount())
        .param("nextAttemptAt", message.nextAttemptAt())
        .param("publishedAt", message.publishedAt())
        .param("lastErrorCode", message.lastErrorCode())
        .param("createdAt", message.createdAt())
        .param("updatedAt", message.updatedAt())
        .update();
  }

  @Override
  public List<OutboxMessage> claimBatch(String workerId, Instant now, int limit) {
    UUID claimToken = UUID.randomUUID();
    return jdbc.sql("""
        WITH candidates AS (
          SELECT message_id
          FROM outbox_messages
          WHERE (
              (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
              OR (status = 'FAILED' AND next_attempt_at IS NOT NULL AND next_attempt_at <= :now)
            )
            AND (claim_until IS NULL OR claim_until < :now)
          ORDER BY created_at
          FOR UPDATE SKIP LOCKED
          LIMIT :limit
        )
        UPDATE outbox_messages AS outbox
        SET claim_token = :claimToken,
            claim_until = :claimUntil,
            attempt_count = outbox.attempt_count + 1,
            updated_at = :now
        FROM candidates
        WHERE outbox.message_id = candidates.message_id
        RETURNING outbox.*
        """)
        .param("now", now)
        .param("limit", limit)
        .param("claimToken", claimToken)
        .param("claimUntil", now.plus(CLAIM_DURATION))
        .query((rs, rowNum) -> map(rs))
        .list();
  }

  @Override
  public boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt) {
    return jdbc.sql("""
        UPDATE outbox_messages
        SET status = 'PUBLISHED', published_at = :publishedAt, claim_token = NULL,
            claim_until = NULL, next_attempt_at = NULL, last_error_code = NULL,
            updated_at = :publishedAt
        WHERE message_id = :messageId AND status IN ('PENDING', 'FAILED')
          AND claim_token = :claimToken
        """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("publishedAt", publishedAt)
        .update() == 1;
  }

  @Override
  public boolean markFailed(UUID messageId, UUID claimToken, String errorCode, Instant nextAttemptAt) {
    Instant updatedAt = Instant.now();
    return jdbc.sql("""
        UPDATE outbox_messages
        SET status = 'FAILED', last_error_code = :errorCode, next_attempt_at = :nextAttemptAt,
            claim_token = NULL, claim_until = NULL, updated_at = :updatedAt
        WHERE message_id = :messageId AND status IN ('PENDING', 'FAILED')
          AND claim_token = :claimToken
        """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("errorCode", errorCode)
        .param("nextAttemptAt", nextAttemptAt)
        .param("updatedAt", updatedAt)
        .update() == 1;
  }

  private static OutboxMessage map(ResultSet rs) throws SQLException {
    return new OutboxMessage(
        rs.getObject("message_id", UUID.class),
        rs.getString("message_kind"),
        rs.getString("message_type"),
        rs.getInt("message_version"),
        rs.getString("exchange_name"),
        rs.getString("routing_key"),
        rs.getObject("operation_id", UUID.class),
        rs.getObject("correlation_id", UUID.class),
        rs.getObject("causation_id", UUID.class),
        rs.getString("idempotency_key"),
        rs.getString("actor"),
        rs.getString("payload"),
        OutboxStatus.valueOf(rs.getString("status")),
        rs.getObject("claim_token", UUID.class),
        instant(rs, "claim_until"),
        rs.getInt("attempt_count"),
        instant(rs, "next_attempt_at"),
        instant(rs, "published_at"),
        rs.getString("last_error_code"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
