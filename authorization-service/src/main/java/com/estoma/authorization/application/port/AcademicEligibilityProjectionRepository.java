package com.estoma.authorization.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AcademicEligibilityProjectionRepository {

  Optional<AcademicEligibilityProjection> findByStudentProfileAndSystem(
      UUID studentAcademicProfileId, String systemCode);

  int upsertIfNewer(AcademicEligibilityProjection projection);

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
