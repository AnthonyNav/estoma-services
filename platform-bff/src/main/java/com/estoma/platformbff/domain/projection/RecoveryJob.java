package com.estoma.platformbff.domain.projection;

import java.time.Instant;
import java.util.UUID;

public record RecoveryJob(
    UUID recoveryJobId,
    String projectionName,
    String mode,
    UUID entityId,
    long generation,
    String ownerLogicalComponentId,
    String queryType,
    UUID operationId,
    String cursor,
    String status,
    Instant startedAt,
    Instant updatedAt,
    Instant completedAt,
    String lastErrorCode,
    UUID currentQueryMessageId) {

  public RecoveryJob(
      UUID recoveryJobId,
      String projectionName,
      String mode,
      UUID entityId,
      long generation,
      String ownerLogicalComponentId,
      String queryType,
      UUID operationId,
      String cursor,
      String status,
      Instant startedAt,
      Instant updatedAt,
      Instant completedAt,
      String lastErrorCode) {
    this(recoveryJobId, projectionName, mode, entityId, generation, ownerLogicalComponentId, queryType,
        operationId, cursor, status, startedAt, updatedAt, completedAt, lastErrorCode, null);
  }
}
