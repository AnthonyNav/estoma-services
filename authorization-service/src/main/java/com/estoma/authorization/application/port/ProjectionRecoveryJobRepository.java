package com.estoma.authorization.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionRecoveryJobRepository {

  Optional<RecoveryJob> findById(UUID recoveryJobId);

  Optional<RecoveryJob> findActive(String projectionType, String scope, String entityKey);

  Optional<RecoveryJob> findWaitingByQueryMessageId(UUID queryMessageId);

  /** Locks the current WAITING_RESULT job for the duration of the caller transaction. */
  Optional<RecoveryJob> findWaitingByQueryMessageIdForUpdate(UUID queryMessageId);

  boolean hasActiveFull(String projectionType);

  boolean hasCompletedFull(String projectionType);

  boolean hasFailedFull(String projectionType);

  List<RecoveryJob> findWaitingBefore(Instant cutoff, int limit);

  /** Latest FULL job is COMPLETED or no FULL job exists. */
  boolean isProjectionTrusted(String projectionType);

  /** Latest ENTITY job for the key is COMPLETED or no ENTITY job exists. */
  boolean isEntityTrusted(String projectionType, String entityKey);

  void insert(RecoveryJob job);

  boolean insertIfAbsent(RecoveryJob job);

  int update(RecoveryJob job, String expectedStatus, UUID expectedCurrentQueryMessageId);

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
