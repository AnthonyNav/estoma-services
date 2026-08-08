package com.estoma.authentication.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@code student_number_login_sync_projection}:
 * technical state tracking the ordered synchronization of a MATRICULA
 * account's {@code loginIdentifier} from {@code StudentNumberCorrected}
 * (data-model.md §9.1). {@code accountId} is a logical reference, not a
 * physical FK, so a conflict can be recorded even when the account does not
 * exist locally.
 */
public interface StudentNumberSyncRepository {

  Optional<StudentNumberSyncRecord> findByAccountId(UUID accountId);

  /** First sighting of this account from {@code StudentNumberCorrected}: initializes the row if absent, no-op otherwise. */
  void ensureExists(UUID accountId, UUID studentAcademicProfileId, Instant updatedAt);

  int markSynchronized(UUID accountId, int newAppliedVersion, Instant updatedAt);

  int markFailed(UUID accountId, String errorCode, Instant updatedAt);

  record StudentNumberSyncRecord(
      UUID accountId, UUID studentAcademicProfileId, int lastAppliedStudentNumberVersion, String synchronizationStatus, String lastErrorCode) {}
}
