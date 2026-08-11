package com.estoma.academic.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AcademicEligibilityPolicyV1Test {
  private final AcademicEligibilityPolicyV1 policy = new AcademicEligibilityPolicyV1();

  @Test void protectsSemesterInvariantAndAppliesTheV1Rule() {
    assertThatThrownBy(() -> profile(0, StudentAcademicProfile.Status.ACTIVE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> profile(15, StudentAcademicProfile.Status.ACTIVE)).isInstanceOf(IllegalArgumentException.class);
    assertThat(policy.isEligible(profile(5, StudentAcademicProfile.Status.ACTIVE))).isFalse();
    assertThat(policy.isEligible(profile(6, StudentAcademicProfile.Status.ACTIVE))).isTrue();
    assertThat(policy.isEligible(profile(14, StudentAcademicProfile.Status.ACTIVE))).isTrue();
    assertThat(policy.isEligible(profile(10, StudentAcademicProfile.Status.INACTIVE))).isFalse();
  }

  private static StudentAcademicProfile profile(int semester, StudentAcademicProfile.Status status) {
    return new StudentAcademicProfile(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "S-1", semester, status, 1);
  }
}
