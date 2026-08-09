package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.CommandIdempotencyRepository;
import com.estoma.authentication.contracts.result.ResultStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed atomic command claim; conflict resolution is delegated to the primary key. */
@Repository
public class JdbcCommandIdempotencyRepository implements CommandIdempotencyRepository {

  private final JdbcClient jdbc;

  JdbcCommandIdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ClaimResult claim(String producer, String operationType, String idempotencyKey, String requestFingerprint, UUID targetAccountId) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO command_idempotency_records (
                    producer, operation_type, idempotency_key, request_fingerprint, target_account_id, status, created_at)
                VALUES (:producer, :operationType, :idempotencyKey, :requestFingerprint, :targetAccountId, 'PROCESSING', :createdAt)
                ON CONFLICT (producer, operation_type, idempotency_key) DO NOTHING
                """)
            .param("producer", producer)
            .param("operationType", operationType)
            .param("idempotencyKey", idempotencyKey)
            .param("requestFingerprint", requestFingerprint)
            .param("targetAccountId", targetAccountId)
            .param("createdAt", Timestamps.toTimestamp(Instant.now()))
            .update();
    if (inserted == 1) {
      return new ClaimResult.Claimed();
    }

    ExistingRecord existing =
        jdbc.sql(
                """
                SELECT request_fingerprint, status, result_data::text AS result_payload
                FROM command_idempotency_records
                WHERE producer = :producer AND operation_type = :operationType AND idempotency_key = :idempotencyKey
                FOR UPDATE
                """)
            .param("producer", producer)
            .param("operationType", operationType)
            .param("idempotencyKey", idempotencyKey)
            .query((rs, rowNum) -> new ExistingRecord(rs.getString("request_fingerprint"), rs.getString("status"), rs.getString("result_payload")))
            .single();
    if (!existing.requestFingerprint().equals(requestFingerprint)) {
      return new ClaimResult.Conflict();
    }
    if ("PROCESSING".equals(existing.status())) {
      return new ClaimResult.InProgress();
    }
    if (existing.resultPayload() == null) {
      throw new IllegalStateException("completed command idempotency record has no terminal result");
    }
    return new ClaimResult.Replay(existing.resultPayload());
  }

  @Override
  public void complete(String producer, String operationType, String idempotencyKey, ResultStatus resultStatus, String resultPayloadJson) {
    jdbc.sql(
            """
            UPDATE command_idempotency_records
            SET status = 'COMPLETED', result_status = :resultStatus, result_data = CAST(:resultPayload AS jsonb),
                error_code = CAST(:resultPayload AS jsonb) -> 'error' ->> 'code', completed_at = :completedAt
            WHERE producer = :producer AND operation_type = :operationType AND idempotency_key = :idempotencyKey
            """)
        .param("resultStatus", resultStatus.name())
        .param("resultPayload", resultPayloadJson)
        .param("completedAt", Timestamps.toTimestamp(Instant.now()))
        .param("producer", producer)
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .update();
  }

  private record ExistingRecord(String requestFingerprint, String status, String resultPayload) {}
}
