package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.IdempotencyRepository;
import com.estoma.platformbff.domain.idempotency.IdempotencyRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
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
    jdbc.sql("""
        INSERT INTO idempotency_records (
          scope_key, actor_account_id, public_operation, idempotency_key,
          request_fingerprint, operation_id, created_at, expires_at
        ) VALUES (:scopeKey, :actorAccountId, :publicOperation, :idempotencyKey,
          :requestFingerprint, :operationId, :createdAt, :expiresAt)
        """)
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

  private static IdempotencyRecord map(ResultSet rs) throws SQLException {
    return new IdempotencyRecord(
        rs.getString("scope_key"),
        rs.getObject("actor_account_id", java.util.UUID.class),
        rs.getString("public_operation"),
        rs.getString("idempotency_key"),
        rs.getString("request_fingerprint"),
        rs.getObject("operation_id", java.util.UUID.class),
        instant(rs, "created_at"),
        instant(rs, "expires_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
