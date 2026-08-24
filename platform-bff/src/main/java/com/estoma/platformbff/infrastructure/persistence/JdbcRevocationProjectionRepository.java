package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.security.RevocationProjectionPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRevocationProjectionRepository implements RevocationProjectionPort {
  private final JdbcClient jdbc;

  public JdbcRevocationProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void linkAccountToPerson(
      UUID accountId, UUID personId, long sourceVersion, Instant updatedAt) {
    jdbc.sql("""
        INSERT INTO account_person_projection(account_id, person_id, source_version, updated_at)
        VALUES (:accountId, :personId, :sourceVersion, :updatedAt)
        ON CONFLICT (account_id) DO UPDATE SET
          person_id = EXCLUDED.person_id,
          source_version = EXCLUDED.source_version,
          updated_at = EXCLUDED.updated_at
        WHERE account_person_projection.source_version < EXCLUDED.source_version
        """)
        .param("accountId", accountId)
        .param("personId", personId)
        .param("sourceVersion", sourceVersion)
        .param("updatedAt", updatedAt)
        .update();
  }

  @Override
  public void revokeSession(
      UUID sessionId,
      long revokedAtEpochSeconds,
      String reasonCode,
      UUID sourceMessageId,
      Instant updatedAt) {
    jdbc.sql("""
        INSERT INTO session_revocations(
          session_id, revoked_at_epoch_seconds, reason_code, source_message_id, updated_at)
        VALUES (:sessionId, :cutoff, :reasonCode, :sourceMessageId, :updatedAt)
        ON CONFLICT (session_id) DO UPDATE SET
          revoked_at_epoch_seconds = EXCLUDED.revoked_at_epoch_seconds,
          reason_code = EXCLUDED.reason_code,
          source_message_id = EXCLUDED.source_message_id,
          updated_at = EXCLUDED.updated_at
        WHERE session_revocations.revoked_at_epoch_seconds <= EXCLUDED.revoked_at_epoch_seconds
        """)
        .param("sessionId", sessionId)
        .param("cutoff", revokedAtEpochSeconds)
        .param("reasonCode", reasonCode)
        .param("sourceMessageId", sourceMessageId)
        .param("updatedAt", updatedAt)
        .update();
  }

  @Override
  public void revokeAccount(
      UUID accountId,
      long cutoff,
      String cutoffKind,
      UUID exceptSessionId,
      String reasonCode,
      UUID sourceMessageId,
      Instant updatedAt) {
    if (("ALL".equals(cutoffKind) && exceptSessionId != null)
        || ("OTHER".equals(cutoffKind) && exceptSessionId == null)
        || (!"ALL".equals(cutoffKind) && !"OTHER".equals(cutoffKind))) {
      throw new IllegalArgumentException("invalid account revocation cutoff shape");
    }
    jdbc.sql("""
        INSERT INTO account_session_revocation_cutoffs(
          account_id, revoked_before_or_at, cutoff_kind, except_session_id,
          reason_code, source_message_id, updated_at)
        VALUES (:accountId, :cutoff, :cutoffKind, :exceptSessionId,
          :reasonCode, :sourceMessageId, :updatedAt)
        ON CONFLICT (account_id) DO UPDATE SET
          revoked_before_or_at = EXCLUDED.revoked_before_or_at,
          cutoff_kind = EXCLUDED.cutoff_kind,
          except_session_id = EXCLUDED.except_session_id,
          reason_code = EXCLUDED.reason_code,
          source_message_id = EXCLUDED.source_message_id,
          updated_at = EXCLUDED.updated_at
        WHERE EXCLUDED.revoked_before_or_at
                > account_session_revocation_cutoffs.revoked_before_or_at
           OR (
                EXCLUDED.revoked_before_or_at
                  = account_session_revocation_cutoffs.revoked_before_or_at
                AND account_session_revocation_cutoffs.cutoff_kind = 'OTHER'
                AND EXCLUDED.cutoff_kind = 'ALL'
              )
        """)
        .param("accountId", accountId)
        .param("cutoff", cutoff)
        .param("cutoffKind", cutoffKind)
        .param("exceptSessionId", exceptSessionId)
        .param("reasonCode", reasonCode)
        .param("sourceMessageId", sourceMessageId)
        .param("updatedAt", updatedAt)
        .update();
  }

  @Override
  public void revokePerson(
      UUID personId,
      long cutoff,
      String reasonCode,
      UUID sourceMessageId,
      Instant updatedAt) {
    jdbc.sql("""
        INSERT INTO person_session_revocation_cutoffs(
          person_id, revoked_before_or_at, reason_code, source_message_id, updated_at)
        VALUES (:personId, :cutoff, :reasonCode, :sourceMessageId, :updatedAt)
        ON CONFLICT (person_id) DO UPDATE SET
          revoked_before_or_at = EXCLUDED.revoked_before_or_at,
          reason_code = EXCLUDED.reason_code,
          source_message_id = EXCLUDED.source_message_id,
          updated_at = EXCLUDED.updated_at
        WHERE person_session_revocation_cutoffs.revoked_before_or_at
                <= EXCLUDED.revoked_before_or_at
        """)
        .param("personId", personId)
        .param("cutoff", cutoff)
        .param("reasonCode", reasonCode)
        .param("sourceMessageId", sourceMessageId)
        .param("updatedAt", updatedAt)
        .update();
  }
}
