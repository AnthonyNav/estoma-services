package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.IdempotencyRepository;
import com.estoma.platformbff.domain.idempotency.IdempotencyRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {
  private final JdbcClient jdbc;

  public JdbcIdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(IdempotencyRecord record) {
    insertStatement(record, false);
  }

  @Override
  public boolean insertIfAbsent(IdempotencyRecord record) {
    return insertStatement(record, true) == 1;
  }

  private int insertStatement(IdempotencyRecord record, boolean ignoreConflict) {
    String conflictClause = ignoreConflict ? " ON CONFLICT DO NOTHING" : "";
    return jdbc.sql("""
        INSERT INTO idempotency_records (
          scope_key, actor_account_id, public_operation, idempotency_key,
          request_fingerprint, operation_id, created_at, expires_at
        ) VALUES (:scopeKey, :actorAccountId, :publicOperation, :idempotencyKey,
          :requestFingerprint, :operationId, :createdAt, :expiresAt)
        """ + conflictClause)
        .param("scopeKey", record.scopeKey())
        .param("actorAccountId", record.actorAccountId())
        .param("publicOperation", record.publicOperation())
        .param("idempotencyKey", record.idempotencyKey())
        .param("requestFingerprint", record.requestFingerprint())
        .param("operationId", record.operationId())
        .param("createdAt", record.createdAt())
        .param("expiresAt", record.expiresAt())
        .update();
  }

  @Override
  public Optional<IdempotencyRecord> findByScopeKey(String scopeKey) {
    return jdbc.sql("SELECT * FROM idempotency_records WHERE scope_key = :scopeKey")
        .param("scopeKey", scopeKey)
        .query((rs, rowNum) -> map(rs))
        .optional();
  }

  @Override
  public Optional<IdempotencyRecord> findByLogicalScope(
      UUID actorAccountId, String publicOperation, String idempotencyKey) {
    return jdbc.sql("""
        SELECT *
        FROM idempotency_records
        WHERE actor_account_id IS NOT DISTINCT FROM :actorAccountId
          AND public_operation = :publicOperation
          AND idempotency_key = :idempotencyKey
        """)
        .param("actorAccountId", actorAccountId)
        .param("publicOperation", publicOperation)
        .param("idempotencyKey", idempotencyKey)
        .query((rs, rowNum) -> map(rs))
        .optional();
  }

  @Override
  public boolean ensureExpiresAt(UUID operationId, Instant expiresAt) {
    return jdbc.sql("""
        UPDATE idempotency_records AS idem
        SET expires_at = COALESCE(idem.expires_at, :expiresAt)
        FROM client_operations AS operation
        WHERE idem.operation_id = :operationId
          AND operation.operation_id = idem.operation_id
          AND operation.status <> 'PENDING'
        """)
        .param("operationId", operationId)
        .param("expiresAt", expiresAt)
        .update() == 1;
  }

  private static IdempotencyRecord map(ResultSet rs) throws SQLException {
    return new IdempotencyRecord(
        rs.getString("scope_key"),
        rs.getObject("actor_account_id", UUID.class),
        rs.getString("public_operation"),
        rs.getString("idempotency_key"),
        rs.getString("request_fingerprint"),
        rs.getObject("operation_id", UUID.class),
        instant(rs, "created_at"),
        instant(rs, "expires_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
