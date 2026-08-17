package com.estoma.authorization.application.projection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AcademicEligibilityProjectionEvent(
    ProjectionEventMetadata metadata,
    UUID eligibilityId,
    UUID studentAcademicProfileId,
    String systemCode,
    UUID accountId,
    boolean eligible,
    int policyVersion,
    int eligibilityVersion,
    int academicProfileVersion,
    Instant evaluatedAt,
    String payloadJson)
    implements ProjectionEvent {

  public AcademicEligibilityProjectionEvent {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(eligibilityId, "eligibilityId");
    Objects.requireNonNull(studentAcademicProfileId, "studentAcademicProfileId");
    Objects.requireNonNull(systemCode, "systemCode");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    Objects.requireNonNull(payloadJson, "payloadJson");
    if (policyVersion <= 0 || eligibilityVersion <= 0 || academicProfileVersion <= 0) {
      throw new IllegalArgumentException("projection versions must be positive");
    }
  }
}
