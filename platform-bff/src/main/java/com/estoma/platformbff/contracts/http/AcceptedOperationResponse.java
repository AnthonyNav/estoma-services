package com.estoma.platformbff.contracts.http;

import java.time.Instant;
import java.util.UUID;

/** Public acknowledgement of an asynchronously submitted owner command. */
public record AcceptedOperationResponse(
    UUID operationId,
    String status,
    String pollPath,
    Instant submittedAt) {

  /** Compatibility constructor for tests/callers written before the public shape was reconciled. */
  public AcceptedOperationResponse(UUID operationId, String status, String pollPath,
      Instant submittedAt, boolean ignoredReusedFlag) {
    this(operationId, status, pollPath, submittedAt);
  }
}
