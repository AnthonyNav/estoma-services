package com.estoma.platformbff.contracts.http;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

/** Public acknowledgement of an asynchronously submitted owner command. */
public record AcceptedOperationResponse(
    @NotNull UUID operationId,
    @NotNull @Pattern(regexp = "PENDING|SUCCEEDED|REJECTED|FAILED|EXPIRED") String status,
    @NotNull String pollPath,
    @NotNull Instant submittedAt) {

  /** Compatibility constructor for tests/callers written before the public shape was reconciled. */
  public AcceptedOperationResponse(UUID operationId, String status, String pollPath,
      Instant submittedAt, boolean ignoredReusedFlag) {
    this(operationId, status, pollPath, submittedAt);
  }
}
