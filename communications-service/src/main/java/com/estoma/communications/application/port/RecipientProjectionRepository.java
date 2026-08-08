package com.estoma.communications.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Derived, read-only view of a recipient (system_contract.md "Ownership").
 * The row is created only by {@link #upsertAccountIfNewer}; {@link
 * #updatePersonIfNewer} only ever updates rows that already exist (see
 * {@code V7__recipient_projection.sql}).
 */
public interface RecipientProjectionRepository {

  record RecipientProjectionRecord(
      UUID accountId,
      UUID personId,
      String displayName,
      String institutionalEmail,
      String personStatus,
      String accountStatus,
      Integer personSourceVersion,
      int accountSourceVersion) {}

  Optional<RecipientProjectionRecord> findByAccountId(UUID accountId);

  /** @return {@code true} if applied (row created or {@code accountSourceVersion} was newer than the stored one). */
  boolean upsertAccountIfNewer(UUID accountId, UUID personId, String accountStatus, int accountSourceVersion, Instant now);

  /** @return the number of rows updated (0 if no row exists yet for this {@code personId}, or none are newer). */
  int updatePersonIfNewer(UUID personId, String displayName, String institutionalEmail, String personStatus, int personSourceVersion, Instant now);
}
