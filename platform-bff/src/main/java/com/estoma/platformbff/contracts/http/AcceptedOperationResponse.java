package com.estoma.platformbff.contracts.http;

import java.time.Instant;
import java.util.UUID;

/** Public acknowledgement of an asynchronously submitted owner command. */
public record AcceptedOperationResponse(
    UUID operationId,
    String status,
    String pollPath,
    Instant submittedAt,
    boolean reused) {}
