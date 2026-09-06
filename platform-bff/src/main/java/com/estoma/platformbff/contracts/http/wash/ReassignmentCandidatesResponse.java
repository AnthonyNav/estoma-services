package com.estoma.platformbff.contracts.http.wash;

import com.estoma.platformbff.contracts.http.Required;
import com.estoma.platformbff.contracts.http.RequiredNullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-ranked, non-reserving resource snapshot for a pending reassignment. */
public record ReassignmentCandidatesResponse(
    @Required UUID washExecutionId,
    long executionVersion,
    @Required Instant snapshotGeneratedAt,
    @RequiredNullable CandidateResponse recommendedCandidate,
    @Required List<CandidateResponse> candidates) {
  public record CandidateResponse(
      @Required UUID cabinId,
      @Required String cabinCode,
      @Required String cabinName,
      @Required UUID tankId,
      @Required String tankCode,
      @Required String tankName,
      @Required @jakarta.validation.constraints.Positive int availableCapacity) {}
}
