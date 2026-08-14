package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCommandIdempotencyRepository implements CommandIdempotencyRepository {

  private static final String COLUMNS =
      "producer, operation_type, idempotency_key, request_fingerprint, status, result_status, "
          + "result_data::text AS result_data, error_code, error_details::text AS error_details, aggregate_id, aggregate_version, "
          + "import_id, plan_item_id, execution_generation, pending_reason, created_at, completed_at, updated_at";
  private final JdbcClient jdbc;

  JdbcCommandIdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<IdempotencyRecord> find(String producer, String operationType, String idempotencyKey) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM command_idempotency_records WHERE producer = :producer "
                + "AND operation_type = :operationType AND idempotency_key = :idempotencyKey")
        .param("producer", producer)
        .param("operationType", operationType)
        .param("idempotencyKey", idempotencyKey)
        .query(IdempotencyRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public boolean insertIfAbsent(IdempotencyRecord record) {
    return jdbc.sql(
            """
            INSERT INTO command_idempotency_records (
              producer, operation_type, idempotency_key, request_fingerprint, status, result_status,
              result_data, error_code, error_details, aggregate_id, aggregate_version, import_id, plan_item_id,
              execution_generation, pending_reason, created_at, completed_at, updated_at
            ) VALUES (
              :producer, :operationType, :idempotencyKey, :requestFingerprint, CAST(:status AS idempotency_status),
              :resultStatus, CAST(:resultData AS jsonb), :errorCode, CAST(:errorDetails AS jsonb), :aggregateId,
              :aggregateVersion, :importId, :planItemId, :executionGeneration, :pendingReason,
              :createdAt, :completedAt, :updatedAt
            ) ON CONFLICT (producer, operation_type, idempotency_key) DO NOTHING
            """)
        .param("producer", record.producer())
        .param("operationType", record.operationType())
        .param("idempotencyKey", record.idempotencyKey())
        .param("requestFingerprint", record.requestFingerprint())
        .param("status", record.status())
        .param("resultStatus", record.resultStatus())
        .param("resultData", record.resultDataJson())
        .param("errorCode", record.errorCode())
        .param("errorDetails", record.errorDetailsJson())
        .param("aggregateId", record.aggregateId())
        .param("aggregateVersion", record.aggregateVersion())
        .param("importId", record.importId())
        .param("planItemId", record.planItemId())
        .param("executionGeneration", record.executionGeneration())
        .param("pendingReason", record.pendingReason())
        .param("createdAt", Timestamps.toTimestamp(record.createdAt()))
        .param("completedAt", Timestamps.toTimestamp(record.completedAt()))
        .param("updatedAt", Timestamps.toTimestamp(record.updatedAt()))
        .update()
        == 1;
  }

  @Override
  public int update(IdempotencyRecord record) {
    return jdbc.sql(
            """
            UPDATE command_idempotency_records SET
              status = CAST(:status AS idempotency_status),
              result_status = :resultStatus, result_data = CAST(:resultData AS jsonb), error_code = :errorCode,
              error_details = CAST(:errorDetails AS jsonb), aggregate_id = :aggregateId, aggregate_version = :aggregateVersion,
              import_id = :importId, plan_item_id = :planItemId, execution_generation = :executionGeneration,
              pending_reason = :pendingReason, completed_at = :completedAt, updated_at = :updatedAt
            WHERE producer = :producer AND operation_type = :operationType AND idempotency_key = :idempotencyKey
              AND request_fingerprint = :requestFingerprint
              AND status = CAST('DEFERRED' AS idempotency_status)
              AND CAST(:status AS idempotency_status) <> CAST('DEFERRED' AS idempotency_status)
            """)
        .param("producer", record.producer())
        .param("operationType", record.operationType())
        .param("idempotencyKey", record.idempotencyKey())
        .param("requestFingerprint", record.requestFingerprint())
        .param("status", record.status())
        .param("resultStatus", record.resultStatus())
        .param("resultData", record.resultDataJson())
        .param("errorCode", record.errorCode())
        .param("errorDetails", record.errorDetailsJson())
        .param("aggregateId", record.aggregateId())
        .param("aggregateVersion", record.aggregateVersion())
        .param("importId", record.importId())
        .param("planItemId", record.planItemId())
        .param("executionGeneration", record.executionGeneration())
        .param("pendingReason", record.pendingReason())
        .param("completedAt", Timestamps.toTimestamp(record.completedAt()))
        .param("updatedAt", Timestamps.toTimestamp(record.updatedAt()))
        .update();
  }
}
