package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.security.RevocationPolicy;
import com.estoma.platformbff.application.security.SessionRevocationChecker;
import com.estoma.platformbff.application.port.ProjectionStatusRepository;
import com.estoma.platformbff.domain.projection.ProjectionStatus;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcSessionRevocationChecker implements SessionRevocationChecker {
  private final JdbcClient jdbc;
  private final ProjectionStatusRepository statuses;

  public JdbcSessionRevocationChecker(JdbcClient jdbc, ProjectionStatusRepository statuses) {
    this.jdbc = jdbc;
    this.statuses = statuses;
  }

  @Override
  public boolean isRevoked(
      UUID accountId, UUID personId, UUID sessionId, long issuedAtEpochSeconds) {
    // Revocation is security-critical: an unavailable or rebuilding projection fails closed.
    if (statuses.findByName("revocation")
        .map(state -> state.status() != ProjectionStatus.READY)
        .orElse(true)) {
      return true;
    }
    boolean sessionRevoked = jdbc.sql(
            "SELECT 1 FROM session_revocations WHERE session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(Integer.class)
        .optional()
        .isPresent();
    if (sessionRevoked) return true;

    var accountCutoff = jdbc.sql("""
        SELECT revoked_before_or_at, cutoff_kind, except_session_id
        FROM account_session_revocation_cutoffs
        WHERE account_id = :accountId
        """)
        .param("accountId", accountId)
        .query((rs, row) -> new AccountCutoff(
            rs.getLong("revoked_before_or_at"),
            rs.getString("cutoff_kind"),
            rs.getObject("except_session_id", UUID.class)))
        .optional()
        .orElse(null);

    Long personCutoff = jdbc.sql("""
        SELECT revoked_before_or_at
        FROM person_session_revocation_cutoffs
        WHERE person_id = :personId
        """)
        .param("personId", personId)
        .query(Long.class)
        .optional()
        .orElse(null);

    RevocationPolicy.AccountCutoff policyCutoff = accountCutoff == null
        ? null
        : new RevocationPolicy.AccountCutoff(
            accountCutoff.cutoff(), "ALL".equals(accountCutoff.kind()), accountCutoff.exceptSessionId());
    return RevocationPolicy.rejects(
        sessionId, issuedAtEpochSeconds, false, personCutoff, policyCutoff);
  }

  private record AccountCutoff(long cutoff, String kind, UUID exceptSessionId) {}
}
