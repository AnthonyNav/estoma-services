package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.CommandIdempotencyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCommandIdempotencyRepository implements CommandIdempotencyRepository {

  private static final String COLUMNS =
      "producer, operation_type, idempotency_key, request_fingerprint, status, result_status, "
          + "result_data::text AS result_data, error_code, aggregate_id, aggregate_version, created_at, completed_at";
  private final JdbcClient jdbc;

  JdbcCommandIdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<IdempotencyRecord> find(String producer, String operationType, String idempotencyKey) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM command_idempotency_records "
                + "WHERE producer = :producer AND operation_type = :operationType AND idempotency_key = :idempotencyKey")
        .param("producer", producer)
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .query(IdempotencyRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public boolean insertIfAbsent(
      String producer,
      String operationType,
      String idempotencyKey,
      String requestFingerprint,
      Instant createdAt) {
    return jdbc.sql(
            """
            INSERT INTO command_idempotency_records (
              producer, operation_type, idempotency_key, request_fingerprint, status, created_at
            ) VALUES (:producer, :operationType, :idempotencyKey, :requestFingerprint, 'IN_PROGRESS', :createdAt)
            ON CONFLICT (producer, operation_type, idempotency_key) DO NOTHING
            """)
        .param("producer", producer)
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .param("requestFingerprint", requestFingerprint)
        .param("createdAt", Timestamps.toTimestamp(createdAt))
        .update()
        == 1;
  }

  @Override
  public int markSucceeded(
      String producer,
      String operationType,
      String idempotencyKey,
      UUID aggregateId,
      int aggregateVersion,
      String resultStatus,
      String resultDataJson,
      Instant completedAt) {
    return jdbc.sql(
            """
            UPDATE command_idempotency_records SET
              status = 'SUCCEEDED', aggregate_id = :aggregateId, aggregate_version = :aggregateVersion,
              result_status = :resultStatus, result_data = CAST(:resultData AS jsonb), completed_at = :completedAt
             WHERE producer = :producer AND operation_type = :operationType AND idempotency_key = :idempotencyKey
               AND status = 'IN_PROGRESS'
            """)
        .param("producer", producer)
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .param("aggregateId", aggregateId)
        .param("aggregateVersion", aggregateVersion)
        .param("resultStatus", resultStatus)
        .param("resultData", resultDataJson)
        .param("completedAt", Timestamps.toTimestamp(completedAt))
        .update();
  }

  @Override
  public int markRejected(
      String producer,
      String operationType,
      String idempotencyKey,
      String errorCode,
      String resultStatus,
      String resultDataJson,
      Instant completedAt) {
    return jdbc.sql(
            """
            UPDATE command_idempotency_records SET
              status = 'REJECTED', error_code = :errorCode, result_status = :resultStatus,
              result_data = CAST(:resultData AS jsonb), completed_at = :completedAt
             WHERE producer = :producer AND operation_type = :operationType AND idempotency_key = :idempotencyKey
               AND status = 'IN_PROGRESS'
            """)
        .param("producer", producer)
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .param("errorCode", errorCode)
        .param("resultStatus", resultStatus)
        .param("resultData", resultDataJson)
        .param("completedAt", Timestamps.toTimestamp(completedAt))
        .update();
  }

  @Override
  public int markFailed(
      String producer,
      String operationType,
      String idempotencyKey,
      String errorCode,
      String resultStatus,
      String resultDataJson,
      Instant completedAt) {
    return markTerminal(
        producer,
        operationType,
        idempotencyKey,
        "FAILED",
        errorCode,
        resultStatus,
        resultDataJson,
        completedAt);
  }

  @Override
  public int markExpired(
      String producer,
      String operationType,
      String idempotencyKey,
      String errorCode,
      String resultStatus,
      String resultDataJson,
      Instant completedAt) {
    return markTerminal(
        producer,
        operationType,
        idempotencyKey,
        "EXPIRED",
        errorCode,
        resultStatus,
        resultDataJson,
        completedAt);
  }

  private int markTerminal(
      String producer,
      String operationType,
      String idempotencyKey,
      String status,
      String errorCode,
      String resultStatus,
      String resultDataJson,
      Instant completedAt) {
    return jdbc.sql(
            """
            UPDATE command_idempotency_records SET
              status = CAST(:status AS idempotency_status), error_code = :errorCode,
              result_status = :resultStatus, result_data = CAST(:resultData AS jsonb), completed_at = :completedAt
             WHERE producer = :producer AND operation_type = :operationType AND idempotency_key = :idempotencyKey
               AND status = 'IN_PROGRESS'
            """)
        .param("producer", producer)
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .param("status", status)
        .param("errorCode", errorCode)
        .param("resultStatus", resultStatus)
        .param("resultData", resultDataJson)
        .param("completedAt", Timestamps.toTimestamp(completedAt))
        .update();
  }
}
