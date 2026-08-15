package com.estoma.authorization.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AcademicEligibilityProjectionRepository {

  Optional<AcademicEligibilityProjection> findByStudentProfileAndSystem(
      UUID studentAcademicProfileId, String systemCode);

  Optional<AcademicEligibilityProjection> findByAccountIdAndSystemCode(
      UUID accountId, String systemCode);

  int upsertIfNewer(AcademicEligibilityProjection projection);

  default int updatePendingReason(UUID studentAcademicProfileId, String systemCode, String pendingReason) {
    return 0;
  }

  record AcademicEligibilityProjection(
      UUID eligibilityId,
      UUID studentAcademicProfileId,
      String systemCode,
      UUID accountId,
      boolean eligible,
      int policyVersion,
      int eligibilityVersion,
      int academicProfileVersion,
      String pendingReason,
      Instant evaluatedAt,
      Instant updatedAt) {}
}
