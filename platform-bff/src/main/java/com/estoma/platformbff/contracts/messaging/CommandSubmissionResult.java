package com.estoma.platformbff.contracts.messaging;

import java.time.Instant;
import java.util.UUID;

public record CommandSubmissionResult(
    UUID operationId,
    String status,
    String pollPath,
    Instant submittedAt,
    boolean reused) {
}
