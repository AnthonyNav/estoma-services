package com.estoma.academic.domain;

/** Versioned domain rule used to calculate one academic eligibility result. */
public interface AcademicEligibilityPolicy {
  String systemCode();

  int version();

  boolean isEligible(StudentAcademicProfile profile);
}
