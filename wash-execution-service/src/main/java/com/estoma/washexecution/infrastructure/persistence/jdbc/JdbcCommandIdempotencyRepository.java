package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.CommandIdempotencyRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommandIdempotencyRepository implements CommandIdempotencyRepository {
  private final JdbcTemplate jdbc;

  public JdbcCommandIdempotencyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insertIfAbsent(
      String producer,
      String operationType,
      String idempotencyKey,
      String requestFingerprint,
      Instant now) {
    return jdbc.update("""
        INSERT INTO command_idempotency_records(
          producer,operation_type,idempotency_key,request_fingerprint,status,created_at)
        VALUES(?,?,?,?, 'IN_PROGRESS', ?)
        ON CONFLICT(producer,operation_type,idempotency_key) DO NOTHING
        """, producer, operationType, idempotencyKey, requestFingerprint, JdbcTemporal.timestamp(now)) == 1;
  }

  @Override
  public Optional<IdempotencyRecord> find(String producer, String operationType, String idempotencyKey) {
    return jdbc.query("""
        SELECT request_fingerprint,status,aggregate_id,aggregate_version,result_json,error_code
        FROM command_idempotency_records
        WHERE producer=? AND operation_type=? AND idempotency_key=?
        """, this::map, producer, operationType, idempotencyKey).stream().findFirst();
  }

  @Override
  public void markSucceeded(
      String producer,
      String operationType,
      String idempotencyKey,
      UUID aggregateId,
      int aggregateVersion,
      String resultJson,
      Instant now) {
    int updated = jdbc.update("""
        UPDATE command_idempotency_records
        SET status='SUCCEEDED',aggregate_id=?,aggregate_version=?,result_json=?,error_code=NULL,completed_at=?
        WHERE producer=? AND operation_type=? AND idempotency_key=? AND status='IN_PROGRESS'
        """, aggregateId, aggregateVersion, resultJson, JdbcTemporal.timestamp(now), producer,
        operationType, idempotencyKey);
    if (updated != 1) throw new IllegalStateException("IDEMPOTENCY_STATE_CONFLICT");
  }

  @Override
  public void markRejected(
      String producer,
      String operationType,
      String idempotencyKey,
      UUID aggregateId,
      String errorCode,
      String resultJson,
      Instant now) {
    int updated = jdbc.update("""
        UPDATE command_idempotency_records
        SET status='REJECTED',aggregate_id=?,aggregate_version=NULL,result_json=?,error_code=?,completed_at=?
        WHERE producer=? AND operation_type=? AND idempotency_key=? AND status='IN_PROGRESS'
        """, aggregateId, resultJson, errorCode, JdbcTemporal.timestamp(now), producer,
        operationType, idempotencyKey);
    if (updated != 1) throw new IllegalStateException("IDEMPOTENCY_STATE_CONFLICT");
  }

  private IdempotencyRecord map(ResultSet rs, int row) throws SQLException {
    return new IdempotencyRecord(
        rs.getString("request_fingerprint"),
        rs.getString("status"),
        rs.getObject("aggregate_id", UUID.class),
        (Integer) rs.getObject("aggregate_version"),
        rs.getString("result_json"),
        rs.getString("error_code"));
  }
}
