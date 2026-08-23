package com.estoma.platformbff.contracts.http;

import java.time.Instant;
import java.util.UUID;

public record OperationResponse(
    UUID operationId,
    String status,
    String publicOperation,
    String errorCode,
    Object data,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {
}
