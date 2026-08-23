package com.estoma.platformbff.domain.operation;

import java.time.Instant;
import java.util.UUID;

public record ClientOperation(
    UUID operationId,
    UUID actorAccountId,
    String publicOperation,
    String commandType,
    String ownerLogicalComponentId,
    String commandRoutingKey,
    UUID correlationId,
    String idempotencyKey,
    OperationStatus status,
    String resultData,
    String errorCode,
    String errorDetails,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    Instant expiresAt) {

  public ClientOperation withTerminalResult(OperationStatus nextStatus, String nextResultData,
      String nextErrorCode, String nextErrorDetails, Instant completed) {
    if (!nextStatus.terminal()) {
      throw new IllegalArgumentException("operation result must be terminal");
    }
    if (status.terminal()) {
      return this;
    }
    return new ClientOperation(operationId, actorAccountId, publicOperation, commandType,
        ownerLogicalComponentId, commandRoutingKey, correlationId, idempotencyKey, nextStatus,
        nextResultData, nextErrorCode, nextErrorDetails, createdAt, completed, completed,
        expiresAt);
  }
}
