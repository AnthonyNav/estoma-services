package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.ProjectionRecoveryQueryRepository;
import com.estoma.platformbff.domain.projection.RecoveryQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcProjectionRecoveryQueryRepository implements ProjectionRecoveryQueryRepository {
  private static final Duration CLAIM_DURATION = Duration.ofSeconds(30);
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public JdbcProjectionRecoveryQueryRepository(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void insert(RecoveryQuery q) {
    jdbc.sql("""
        INSERT INTO projection_recovery_messages
          (message_id, recovery_job_id, generation, message_type, message_version, exchange_name,
           routing_key, correlation_id, causation_id, idempotency_key, actor, payload, status,
           claim_token, claim_until, attempt_count, next_attempt_at, published_at, last_error_code,
           created_at, updated_at)
        VALUES (:messageId, :jobId, :generation, :messageType, :messageVersion, :exchangeName,
          :routingKey, :correlationId, :causationId, :idempotencyKey, CAST(:actor AS jsonb),
          CAST(:payload AS jsonb), :status, :claimToken, :claimUntil, :attemptCount, :nextAttemptAt,
          :publishedAt, :lastErrorCode, :createdAt, :updatedAt)
        """)
        .param("messageId", q.messageId()).param("jobId", q.recoveryJobId()).param("generation", q.generation())
        .param("messageType", q.messageType()).param("messageVersion", q.messageVersion()).param("exchangeName", q.exchangeName())
        .param("routingKey", q.routingKey()).param("correlationId", q.correlationId()).param("causationId", q.causationId())
        .param("idempotencyKey", q.idempotencyKey()).param("actor", q.actorJson()).param("payload", mapper.writeValueAsString(q.payload()))
        .param("status", q.status()).param("claimToken", q.claimToken()).param("claimUntil", JdbcTimes.timestamp(q.claimUntil()))
        .param("attemptCount", q.attemptCount()).param("nextAttemptAt", JdbcTimes.timestamp(q.nextAttemptAt())).param("publishedAt", JdbcTimes.timestamp(q.publishedAt()))
        .param("lastErrorCode", q.lastErrorCode()).param("createdAt", JdbcTimes.timestamp(q.createdAt())).param("updatedAt", JdbcTimes.timestamp(q.updatedAt())).update();
  }

  @Override
  public Optional<RecoveryQuery> claimNext(String workerId, Instant now) {
    UUID token = UUID.randomUUID();
    return jdbc.sql("""
        WITH candidate AS (
          SELECT message_id FROM projection_recovery_messages
          WHERE ((status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
             OR (status = 'FAILED' AND next_attempt_at IS NOT NULL AND next_attempt_at <= :now))
            AND (claim_until IS NULL OR claim_until < :now)
          ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
        )
        UPDATE projection_recovery_messages m
        SET claim_token = :claimToken, claim_until = :claimUntil,
            attempt_count = m.attempt_count + 1, updated_at = :now
        FROM candidate WHERE m.message_id = candidate.message_id
        RETURNING m.*
        """)
        .param("now", JdbcTimes.timestamp(now)).param("claimToken", token).param("claimUntil", JdbcTimes.timestamp(now.plus(CLAIM_DURATION)))
        .query((rs, row) -> map(rs)).optional();
  }

  @Override
  public boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt) {
    return jdbc.sql("""
        UPDATE projection_recovery_messages SET status = 'PUBLISHED', published_at = :publishedAt,
          claim_token = NULL, claim_until = NULL, next_attempt_at = NULL, last_error_code = NULL, updated_at = :publishedAt
        WHERE message_id = :messageId AND status IN ('PENDING', 'FAILED') AND claim_token = :claimToken
        """).param("messageId", messageId).param("claimToken", claimToken).param("publishedAt", JdbcTimes.timestamp(publishedAt)).update() == 1;
  }

  @Override
  public boolean markFailed(UUID messageId, UUID claimToken, String errorCode, Instant nextAttemptAt) {
    Instant now = Instant.now();
    return jdbc.sql("""
        UPDATE projection_recovery_messages SET status = 'FAILED', last_error_code = :errorCode,
          next_attempt_at = :nextAttemptAt, claim_token = NULL, claim_until = NULL, updated_at = :updatedAt
        WHERE message_id = :messageId AND status IN ('PENDING', 'FAILED') AND claim_token = :claimToken
        """).param("messageId", messageId).param("claimToken", claimToken).param("errorCode", errorCode)
        .param("nextAttemptAt", JdbcTimes.timestamp(nextAttemptAt)).param("updatedAt", JdbcTimes.timestamp(now)).update() == 1;
  }

  @SuppressWarnings("unchecked")
  private RecoveryQuery map(ResultSet rs) throws SQLException {
    return new RecoveryQuery(rs.getObject("message_id", UUID.class), rs.getObject("recovery_job_id", UUID.class),
        rs.getLong("generation"), rs.getString("message_type"), rs.getInt("message_version"), rs.getString("exchange_name"),
        rs.getString("routing_key"), rs.getObject("correlation_id", UUID.class), rs.getObject("causation_id", UUID.class),
        rs.getString("idempotency_key"), rs.getString("actor"), mapper.readValue(rs.getString("payload"), Map.class),
        rs.getString("status"), rs.getObject("claim_token", UUID.class), JdbcTimes.instant(rs, "claim_until"), rs.getInt("attempt_count"),
        JdbcTimes.instant(rs, "next_attempt_at"), JdbcTimes.instant(rs, "published_at"), rs.getString("last_error_code"),
        JdbcTimes.instant(rs, "created_at"), JdbcTimes.instant(rs, "updated_at"));
  }
}
