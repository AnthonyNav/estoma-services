package com.estoma.academic.domain;

/** V1 grants professional-practice access only to active students in semester six or later. */
public final class AcademicEligibilityPolicyV1 implements AcademicEligibilityPolicy {
  public static final String SYSTEM_CODE = "PRACTICAS_PROFESIONALES";
  private final int version;

  public AcademicEligibilityPolicyV1() { this(1); }
  public AcademicEligibilityPolicyV1(int version) {
    if (version != 1) throw new IllegalArgumentException("AcademicEligibilityPolicyV1 is fixed at version 1");
    this.version = 1;
  }
  @Override public String systemCode() { return SYSTEM_CODE; }
  @Override public int version() { return version; }
  @Override
  public boolean isEligible(StudentAcademicProfile profile) {
    return profile.status() == StudentAcademicProfile.Status.ACTIVE && profile.currentSemester() >= 6;
  }
}
