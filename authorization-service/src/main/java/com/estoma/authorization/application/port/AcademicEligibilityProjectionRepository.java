package com.estoma.authorization.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcademicEligibilityProjectionRepository {

  Optional<AcademicEligibilityProjection> findByStudentProfileAndSystem(
      UUID studentAcademicProfileId, String systemCode);

  Optional<AcademicEligibilityProjection> findByAccountIdAndSystemCode(
      UUID accountId, String systemCode);

  List<UUID> findEligibleAccountIds(String systemCode);

  int upsertIfNewer(AcademicEligibilityProjection projection);

  int updatePendingReason(UUID studentAcademicProfileId, String systemCode, String pendingReason);

  int clearAll();

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
