package com.estoma.authentication.application.port;

import com.estoma.authentication.domain.session.Session;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Session}. No version column on this table; updates are guarded by state, not a counter. */
public interface SessionRepository {

  Optional<Session> findById(UUID sessionId);

  /** Acquires the session row lock for refresh, replay and password flows. */
  Optional<Session> findByIdForUpdate(UUID sessionId);

  Optional<Session> findByRefreshTokenHash(String refreshTokenHash);

  void insert(Session session);

  /** Rotation or single-session revocation, unconditional by {@code sessionId}. */
  int update(Session session);

  /**
   * {@code RevokeAllAccountSessions} (commands.md §5.1) / the session-revocation half of {@code
   * DeactivateAccount} (commands.md §4.3): revokes every currently-valid session for the account in
   * one statement.
   *
   * @return the number of sessions revoked.
   */
  int revokeAllActiveForAccount(UUID accountId, String reason, Instant now);

  /** Revokes currently-valid sessions for an account except its retained current session. */
  int revokeOtherActiveForAccount(UUID accountId, UUID retainedSessionId, String reason, Instant now);
}
