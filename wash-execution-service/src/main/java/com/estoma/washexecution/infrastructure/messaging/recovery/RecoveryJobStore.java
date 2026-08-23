package com.estoma.washexecution.infrastructure.messaging.recovery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecoveryJobStore {
  enum Scope { FULL, ENTITY }
  enum Status { PENDING, WAITING_RESULT, COMPLETED, FAILED }

  record Job(UUID jobId, String projectionName, Scope scope, String entityKey, LocalDate serviceDate,
      Status status, String cursor, int queryAttempt, String currentQueryType, UUID currentQueryMessageId,
      UUID currentOperationId, UUID currentCorrelationId, String lastErrorCode, Instant updatedAt) {}

  boolean hasCompletedFull(String projectionName, LocalDate serviceDate);
  Optional<Job> findActiveFull(String projectionName, LocalDate serviceDate);
  Optional<Job> findActiveEntity(String projectionName, String entityKey);
  Job createFull(String projectionName, LocalDate serviceDate, Instant now);
  Job createEntity(String projectionName, String entityKey, Instant now);
  List<Job> claimPending(int limit, Instant now, UUID claimToken, Instant leaseUntil);
  boolean markWaiting(UUID jobId, UUID claimToken, String queryType, UUID queryMessageId,
      UUID operationId, UUID correlationId, Instant now);
  Optional<Job> lockWaitingByQueryMessageId(UUID queryMessageId);
  void advancePage(UUID jobId, String nextCursor, Instant now);
  void complete(UUID jobId, Instant now);
  void fail(UUID jobId, String errorCode, Instant now);
  int requeueTimedOut(Instant threshold, Instant now);
}
