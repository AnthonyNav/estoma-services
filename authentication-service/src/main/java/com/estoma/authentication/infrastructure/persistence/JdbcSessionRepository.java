package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.domain.session.Session;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link SessionRepository}. No version column: sessions are guarded by state (revoked/expired), not a counter. */
@Repository
class JdbcSessionRepository implements SessionRepository {

  private static final String COLUMNS =
      """
       session_id, account_id, refresh_token_hash, previous_refresh_token_hash, previous_refresh_valid_until, auth_state,
       device_name, ip_address, user_agent, created_at, idle_expires_at, absolute_expires_at, last_used_at, revoked_at, revocation_reason
      """;

  private final JdbcClient jdbc;

  JdbcSessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Session> findById(UUID sessionId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM sessions WHERE session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(JdbcSessionRepository::mapRow)
        .optional();
  }

  @Override
  public Optional<Session> findByIdForUpdate(UUID sessionId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM sessions WHERE session_id = :sessionId FOR UPDATE")
        .param("sessionId", sessionId)
        .query(JdbcSessionRepository::mapRow)
        .optional();
  }

  @Override
  public Optional<Session> findByRefreshTokenHash(String refreshTokenHash) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM sessions WHERE refresh_token_hash = :hash OR previous_refresh_token_hash = :hash")
        .param("hash", refreshTokenHash)
        .query(JdbcSessionRepository::mapRow)
        .optional();
  }

  @Override
  public void insert(Session session) {
    jdbc.sql(
            """
            INSERT INTO sessions (
               session_id, account_id, refresh_token_hash, previous_refresh_token_hash, previous_refresh_valid_until, auth_state,
               device_name, ip_address, user_agent, created_at, expires_at, idle_expires_at, absolute_expires_at, last_used_at, revoked_at, revocation_reason
            ) VALUES (
               :sessionId, :accountId, :refreshTokenHash, :previousRefreshTokenHash, :previousRefreshValidUntil, :authState,
               :deviceName, :ipAddress, :userAgent, :createdAt, :idleExpiresAt, :idleExpiresAt, :absoluteExpiresAt, :lastUsedAt, :revokedAt, :revocationReason
            )
            """)
        .param("sessionId", session.sessionId())
        .param("accountId", session.accountId())
        .param("refreshTokenHash", session.refreshTokenHash())
        .param("previousRefreshTokenHash", session.previousRefreshTokenHash())
        .param("previousRefreshValidUntil", Timestamps.toTimestamp(session.previousRefreshValidUntil()))
        .param("authState", session.authState().name())
        .param("deviceName", session.deviceName())
        .param("ipAddress", session.ipAddress())
        .param("userAgent", session.userAgent())
        .param("createdAt", Timestamps.toTimestamp(session.createdAt()))
        .param("idleExpiresAt", Timestamps.toTimestamp(session.idleExpiresAt()))
        .param("absoluteExpiresAt", Timestamps.toTimestamp(session.absoluteExpiresAt()))
        .param("lastUsedAt", Timestamps.toTimestamp(session.lastUsedAt()))
        .param("revokedAt", Timestamps.toTimestamp(session.revokedAt()))
        .param("revocationReason", session.revocationReason())
        .update();
  }

  @Override
  public int update(Session session) {
    return jdbc.sql(
            """
            UPDATE sessions SET
              refresh_token_hash = :refreshTokenHash,
              previous_refresh_token_hash = :previousRefreshTokenHash,
              previous_refresh_valid_until = :previousRefreshValidUntil,
              auth_state = :authState,
              expires_at = :idleExpiresAt,
              idle_expires_at = :idleExpiresAt,
              absolute_expires_at = :absoluteExpiresAt,
              last_used_at = :lastUsedAt,
              revoked_at = :revokedAt,
              revocation_reason = :revocationReason
            WHERE session_id = :sessionId
            """)
        .param("refreshTokenHash", session.refreshTokenHash())
        .param("previousRefreshTokenHash", session.previousRefreshTokenHash())
        .param("previousRefreshValidUntil", Timestamps.toTimestamp(session.previousRefreshValidUntil()))
        .param("authState", session.authState().name())
        .param("idleExpiresAt", Timestamps.toTimestamp(session.idleExpiresAt()))
        .param("absoluteExpiresAt", Timestamps.toTimestamp(session.absoluteExpiresAt()))
        .param("lastUsedAt", Timestamps.toTimestamp(session.lastUsedAt()))
        .param("revokedAt", Timestamps.toTimestamp(session.revokedAt()))
        .param("revocationReason", session.revocationReason())
        .param("sessionId", session.sessionId())
        .update();
  }

  @Override
  public int revokeAllActiveForAccount(UUID accountId, String reason, Instant now) {
    return jdbc.sql(
            """
            UPDATE sessions SET revoked_at = :revokedAt, revocation_reason = :reason
            WHERE account_id = :accountId AND revoked_at IS NULL AND idle_expires_at > :now AND absolute_expires_at > :now
            """)
        .param("revokedAt", Timestamps.toTimestamp(now))
        .param("reason", reason)
        .param("accountId", accountId)
        .param("now", Timestamps.toTimestamp(now))
        .update();
  }

  @Override
  public int revokeOtherActiveForAccount(UUID accountId, UUID retainedSessionId, String reason, Instant now) {
    return jdbc.sql(
            """
            UPDATE sessions SET revoked_at = :revokedAt, revocation_reason = :reason
            WHERE account_id = :accountId AND session_id <> :retainedSessionId
              AND revoked_at IS NULL AND idle_expires_at > :now AND absolute_expires_at > :now
            """)
        .param("revokedAt", Timestamps.toTimestamp(now))
        .param("reason", reason)
        .param("accountId", accountId)
        .param("retainedSessionId", retainedSessionId)
        .param("now", Timestamps.toTimestamp(now))
        .update();
  }

  private static Session mapRow(ResultSet rs, int rowNum) throws SQLException {
    return Session.reconstitute(
        rs.getObject("session_id", UUID.class),
        rs.getObject("account_id", UUID.class),
        rs.getString("refresh_token_hash"),
        rs.getString("previous_refresh_token_hash"),
        Timestamps.toInstant(rs, "previous_refresh_valid_until"),
        Session.AuthState.valueOf(rs.getString("auth_state")),
        rs.getString("device_name"),
        rs.getString("ip_address"),
        rs.getString("user_agent"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "idle_expires_at"),
        Timestamps.toInstant(rs, "absolute_expires_at"),
        Timestamps.toInstant(rs, "last_used_at"),
        Timestamps.toInstant(rs, "revoked_at"),
        rs.getString("revocation_reason"));
  }
}
