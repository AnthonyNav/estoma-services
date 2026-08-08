package com.estoma.users.persistence;

import com.estoma.users.application.IdempotencyRecord;
import com.estoma.users.application.port.IdempotencyPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link IdempotencyPort}. The whole
 * claim-or-read-existing guarantee lives in one statement: an {@code
 * INSERT ... ON CONFLICT (producer, idempotency_key) DO UPDATE ...
 * RETURNING} — not "check, then insert if absent", which would be a
 * TOCTOU race under concurrent redelivery. The {@code DO UPDATE} branch
 * (a genuine no-op — it reassigns the conflict-target column to its own
 * {@code EXCLUDED} value) forces Postgres to take the row-level lock and
 * wait for a concurrent claimant's transaction to finish before
 * returning: two concurrent claims for the same key can never both
 * proceed to execute the command's effect.
 */
@Repository
class JdbcIdempotencyRepository implements IdempotencyPort {

  private static final String COLUMNS =
      """
      producer, idempotency_key, message_type, aggregate_id, request_fingerprint,
      claimed_by_message_id, terminal_status, aggregate_version, error_code,
      created_at, completed_at
      """;

  private final JdbcClient jdbc;

  JdbcIdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public IdempotencyRecord claim(
      String producer,
      String idempotencyKey,
      String messageType,
      UUID aggregateId,
      String requestFingerprint,
      UUID messageId,
      Instant now) {
    return jdbc.sql(
            """
            INSERT INTO idempotency_records (
              producer, idempotency_key, message_type, aggregate_id, request_fingerprint,
              claimed_by_message_id, terminal_status, created_at
            ) VALUES (
              :producer, :idempotencyKey, :messageType, :aggregateId, :requestFingerprint,
              :messageId, 'IN_PROGRESS', :now
            )
            ON CONFLICT (producer, idempotency_key) DO UPDATE
              SET idempotency_key = EXCLUDED.idempotency_key
            RETURNING """
                + " "
                + COLUMNS)
        .param("producer", producer)
        .param("idempotencyKey", idempotencyKey)
        .param("messageType", messageType)
        .param("aggregateId", aggregateId)
        .param("requestFingerprint", requestFingerprint)
        .param("messageId", messageId)
        .param("now", Timestamps.toTimestamp(now))
        .query(IdempotencyRowMapper.INSTANCE)
        .single();
  }

  @Override
  public void markSucceeded(String producer, String idempotencyKey, int aggregateVersion, Instant completedAt) {
    jdbc.sql(
            """
            UPDATE idempotency_records
               SET terminal_status = 'SUCCEEDED', aggregate_version = :aggregateVersion, completed_at = :completedAt
             WHERE producer = :producer AND idempotency_key = :idempotencyKey
            """)
        .param("aggregateVersion", aggregateVersion)
        .param("completedAt", Timestamps.toTimestamp(completedAt))
        .param("producer", producer)
        .param("idempotencyKey", idempotencyKey)
        .update();
  }

  @Override
  public void markRejected(String producer, String idempotencyKey, String errorCode, Instant completedAt) {
    jdbc.sql(
            """
            UPDATE idempotency_records
               SET terminal_status = 'REJECTED', error_code = :errorCode, completed_at = :completedAt
             WHERE producer = :producer AND idempotency_key = :idempotencyKey
            """)
        .param("errorCode", errorCode)
        .param("completedAt", Timestamps.toTimestamp(completedAt))
        .param("producer", producer)
        .param("idempotencyKey", idempotencyKey)
        .update();
  }
}
