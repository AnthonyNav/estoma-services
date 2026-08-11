package com.estoma.academic.domain;

/** V1 grants professional-practice access only to active students in semester six or later. */
public final class AcademicEligibilityPolicyV1 {
  public static final String SYSTEM_CODE = "PRACTICAS_PROFESIONALES";
  private final int version;

  public AcademicEligibilityPolicyV1() { this(1); }
  public AcademicEligibilityPolicyV1(int version) {
    if (version < 1) throw new IllegalArgumentException("policy version must be positive");
    this.version = version;
  }
  public int version() { return version; }
  public boolean isEligible(StudentAcademicProfile profile) {
    return profile.status() == StudentAcademicProfile.Status.ACTIVE && profile.currentSemester() >= 6;
  }
}
