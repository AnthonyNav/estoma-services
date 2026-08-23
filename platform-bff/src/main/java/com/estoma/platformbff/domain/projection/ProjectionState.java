package com.estoma.platformbff.domain.projection;

import java.time.Instant;

public record ProjectionState(
    String projectionName,
    ProjectionStatus status,
    Instant lastAppliedAt,
    String lastErrorCode,
    long generation,
    Instant updatedAt) {
}
