package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionRecoveryJobRepository {

  Optional<RecoveryJob> claimNextPending(UUID claimToken, Instant leaseUntil);

  RecoveryJob createEntity(UUID accountId, Instant now);

  RecoveryJob createFull(Instant now);

  Optional<RecoveryJob> findWaitingByQueryMessageIdForUpdate(UUID queryMessageId);

  boolean markWaiting(
      UUID recoveryJobId,
      UUID claimToken,
      String queryType,
      UUID queryMessageId,
      UUID operationId,
      UUID correlationId,
      int queryAttempt);

  boolean markPendingAfterPage(UUID recoveryJobId, UUID queryMessageId, String cursor);

  boolean markCompleted(UUID recoveryJobId, UUID queryMessageId);

  boolean markFailed(UUID recoveryJobId, UUID queryMessageId, String errorCode);

  default int requeueExpiredWaiting(Instant cutoff, String errorCode) {
    return 0;
  }

  void clearAuthorizationProjection();

  boolean hasActiveFullRecovery();

  boolean hasFailedFullRecovery();

  default boolean hasActiveEntityRecovery(UUID accountId) {
    return false;
  }

  default boolean hasFailedEntityRecovery(UUID accountId) {
    return false;
  }

  enum Scope {
    FULL,
    ENTITY
  }

  enum Status {
    PENDING,
    WAITING_RESULT,
    COMPLETED,
    FAILED
  }

  record RecoveryJob(
      UUID recoveryJobId,
      String projectionType,
      Scope scope,
      String entityKey,
      Status status,
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
