package com.estoma.academic.application;

import com.estoma.academic.domain.AcademicEligibilityPolicyV1;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Explicit activation seam; activation changes only evaluations caused after it, never mass-reprocesses profiles. */
@Component
public class AcademicEligibilityPolicyRegistry {
  private final AtomicReference<AcademicEligibilityPolicyV1> active = new AtomicReference<>(new AcademicEligibilityPolicyV1());
  public AcademicEligibilityPolicyV1 active() { return active.get(); }
  public void activateForEvaluation(int version) { active.set(new AcademicEligibilityPolicyV1(version)); }
}
