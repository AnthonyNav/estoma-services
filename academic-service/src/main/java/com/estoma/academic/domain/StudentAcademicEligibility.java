package com.estoma.academic.domain;

import java.util.UUID;

public record StudentAcademicEligibility(
    UUID id, UUID studentAcademicProfileId, String systemCode, boolean eligible,
    int policyVersion, int eligibilityVersion, int academicProfileVersion) {
  public StudentAcademicEligibility {
    if (id == null || studentAcademicProfileId == null || systemCode == null || systemCode.isBlank()
        || policyVersion < 1 || eligibilityVersion < 1 || academicProfileVersion < 1) {
      throw new IllegalArgumentException("invalid eligibility state");
    }
  }
}
