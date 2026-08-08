package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.SecurityRpcIdempotencyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link SecurityRpcIdempotencyRepository}. {@link
 * #tryClaim} runs inside the caller's (the RPC dispatcher's) ambient
 * transaction -- claim, validate, execute, and mark all happen in one
 * transaction per commands.md §7.1, so this class opens no transaction of
 * its own.
 */
@Repository
class JdbcSecurityRpcIdempotencyRepository implements SecurityRpcIdempotencyRepository {

  private final JdbcClient jdbc;

  JdbcSecurityRpcIdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ClaimResult tryClaim(
      String operationType, String idempotencyKey, UUID accountId, UUID requestedByAccountId, String requestFingerprint, Instant expiresAt) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO security_rpc_idempotency_records (
                  operation_type, idempotency_key, account_id, requested_by_account_id, request_fingerprint, status, expires_at
                ) VALUES (
                  :operationType, :idempotencyKey, :accountId, :requestedByAccountId, :requestFingerprint,
                  CAST('PROCESSING' AS security_rpc_idempotency_status), :expiresAt
                )
                ON CONFLICT (operation_type, idempotency_key) DO NOTHING
                """)
            .param("operationType", operationType)
            .param("idempotencyKey", idempotencyKey)
            .param("accountId", accountId)
            .param("requestedByAccountId", requestedByAccountId)
            .param("requestFingerprint", requestFingerprint)
            .param("expiresAt", Timestamps.toTimestamp(expiresAt))
            .update();

    if (inserted == 1) {
      return new Claimed();
    }

    Optional<ExistingRecord> existing =
        jdbc.sql(
                "SELECT status, request_fingerprint, error_code FROM security_rpc_idempotency_records "
                    + "WHERE operation_type = :operationType AND idempotency_key = :idempotencyKey")
            .param("operationType", operationType)
            .param("idempotencyKey", idempotencyKey)
            .query((rs, rowNum) -> new ExistingRecord(rs.getString("status"), rs.getString("request_fingerprint"), rs.getString("error_code")))
            .optional();

    // A concurrent transaction deleted the row between the failed insert and this read (e.g. expired-record
    // cleanup) -- treat as if this claim can proceed by retrying the insert once is overkill for v1; surface
    // it as a claim so the caller's insert-or-conflict outcome stays simple. This is expected to be
    // exceedingly rare (a cleanup job racing a live claim within the same instant).
    ExistingRecord record = existing.orElseThrow(() -> new IllegalStateException("idempotency record vanished mid-claim"));

    if (!record.requestFingerprint.equals(requestFingerprint)) {
      return new FingerprintMismatch();
    }
    return switch (record.status) {
      case "COMPLETED" -> new AlreadyCompleted();
      case "FAILED" -> new AlreadyFailed(record.errorCode);
      default -> new Claimed(); // PROCESSING with a matching fingerprint: safe to proceed.
    };
  }

  @Override
  public void markCompleted(String operationType, String idempotencyKey, Instant processedAt) {
    jdbc.sql(
            """
            UPDATE security_rpc_idempotency_records
            SET status = CAST('COMPLETED' AS security_rpc_idempotency_status), processed_at = :processedAt
            WHERE operation_type = :operationType AND idempotency_key = :idempotencyKey
            """)
        .param("processedAt", Timestamps.toTimestamp(processedAt))
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .update();
  }

  @Override
  public void markFailed(String operationType, String idempotencyKey, String errorCode, Instant processedAt) {
    jdbc.sql(
            """
            UPDATE security_rpc_idempotency_records
            SET status = CAST('FAILED' AS security_rpc_idempotency_status), error_code = :errorCode, processed_at = :processedAt
            WHERE operation_type = :operationType AND idempotency_key = :idempotencyKey
            """)
        .param("errorCode", errorCode)
        .param("processedAt", Timestamps.toTimestamp(processedAt))
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .update();
  }

  private record ExistingRecord(String status, String requestFingerprint, String errorCode) {}
}
