package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link PasswordRecoveryRepository}. */
@Repository
class JdbcPasswordRecoveryRepository implements PasswordRecoveryRepository {

  private static final String COLUMNS = "recovery_id, account_id, token_hash, created_at, expires_at, used_at, failed_attempts";

  private final JdbcClient jdbc;

  JdbcPasswordRecoveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PasswordRecoveryRequest> findById(UUID recoveryId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM password_recovery_requests WHERE recovery_id = :recoveryId")
        .param("recoveryId", recoveryId)
        .query(JdbcPasswordRecoveryRepository::mapRow)
        .optional();
  }

  @Override
  public void insert(PasswordRecoveryRequest request) {
    jdbc.sql(
            """
            INSERT INTO password_recovery_requests (recovery_id, account_id, token_hash, created_at, expires_at, used_at, failed_attempts)
            VALUES (:recoveryId, :accountId, :tokenHash, :createdAt, :expiresAt, :usedAt, :failedAttempts)
            """)
        .param("recoveryId", request.recoveryId())
        .param("accountId", request.accountId())
        .param("tokenHash", request.tokenHash())
        .param("createdAt", Timestamps.toTimestamp(request.createdAt()))
        .param("expiresAt", Timestamps.toTimestamp(request.expiresAt()))
        .param("usedAt", Timestamps.toTimestamp(request.usedAt()))
        .param("failedAttempts", request.failedAttempts())
        .update();
  }

  @Override
  public int update(PasswordRecoveryRequest request) {
    return jdbc.sql("UPDATE password_recovery_requests SET failed_attempts = :failedAttempts WHERE recovery_id = :recoveryId")
        .param("failedAttempts", request.failedAttempts())
        .param("recoveryId", request.recoveryId())
        .update();
  }

  @Override
  public int markUsedIfValid(UUID recoveryId, Instant now, Instant usedAt) {
    return jdbc.sql(
            """
            UPDATE password_recovery_requests SET used_at = :usedAt
            WHERE recovery_id = :recoveryId AND used_at IS NULL AND expires_at > :now
            """)
        .param("usedAt", Timestamps.toTimestamp(usedAt))
        .param("recoveryId", recoveryId)
        .param("now", Timestamps.toTimestamp(now))
        .update();
  }

  private static PasswordRecoveryRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
    return PasswordRecoveryRequest.reconstitute(
        rs.getObject("recovery_id", UUID.class),
        rs.getObject("account_id", UUID.class),
        rs.getString("token_hash"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "expires_at"),
        Timestamps.toInstant(rs, "used_at"),
        rs.getInt("failed_attempts"));
  }
}
