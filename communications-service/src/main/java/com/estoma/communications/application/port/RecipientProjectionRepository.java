package com.estoma.communications.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Derived, read-only recipient view owned locally by Communications. */
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

  boolean upsertAccountIfNewer(UUID accountId, UUID personId, String accountStatus, int accountSourceVersion, Instant now);

  int updatePersonIfNewer(UUID personId, String displayName, String institutionalEmail, String personStatus, int personSourceVersion, Instant now);

  /** Applies PersonUpdated without erasing the status last supplied by Users. */
  int updatePersonDetailsIfNewer(UUID personId, String displayName, String institutionalEmail, int personSourceVersion, Instant now);

  /** Applies PersonActivated/PersonDeactivated without erasing identity/contact fields. */
  int updatePersonStatusIfNewer(UUID personId, String personStatus, int personSourceVersion, Instant now);
}
