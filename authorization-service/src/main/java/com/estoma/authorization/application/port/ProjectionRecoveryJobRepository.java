package com.estoma.authorization.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionRecoveryJobRepository {

  Optional<RecoveryJob> findById(UUID recoveryJobId);

  void insert(RecoveryJob job);

  record RecoveryJob(
      UUID recoveryJobId,
      String projectionType,
      String scope,
      String entityKey,
      String status,
      String cursor,
      int queryAttempt,
      String currentQueryType,
      UUID currentQueryMessageId,
      UUID currentOperationId,
      UUID currentCorrelationId,
      int attempts,
      String lastErrorCode,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt,
      UUID claimToken,
      Instant leaseUntil) {}
}
