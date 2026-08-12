package com.estoma.academic.application;

import com.estoma.academic.domain.AcademicEligibilityPolicy;
import com.estoma.academic.domain.AcademicEligibilityPolicyV1;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Explicit activation seam; activation changes only evaluations caused after it, never mass-reprocesses profiles. */
@Component
public class AcademicEligibilityPolicyRegistry {
  private final AtomicReference<AcademicEligibilityPolicy> active = new AtomicReference<>(new AcademicEligibilityPolicyV1());
  public AcademicEligibilityPolicy active() { return active.get(); }
  /**
   * Legacy test hook retained as a strict V1 reset. Future policies must use
   * the typed policy seam below and provide their own semantics.
   */
  public void activateForEvaluation(int version) {
    if (version != 1) throw new IllegalArgumentException("V1 is the only production policy available");
    active.set(new AcademicEligibilityPolicyV1());
  }

  /** Test seam for exercising future policy behavior without relabelling V1. */
  public void activateForEvaluation(AcademicEligibilityPolicy policy) {
    if (policy == null || policy.systemCode() == null || policy.systemCode().isBlank() || policy.version() < 1) {
      throw new IllegalArgumentException("invalid eligibility policy");
    }
    active.set(policy);
  }
}
