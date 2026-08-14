package com.estoma.authorization.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommandIdempotencyRepository {

  Optional<IdempotencyRecord> find(String producer, String operationType, String idempotencyKey);

  boolean insertIfAbsent(IdempotencyRecord record);

  int update(IdempotencyRecord record);

  record IdempotencyRecord(
      String producer,
      String operationType,
      String idempotencyKey,
      String requestFingerprint,
      String status,
      String resultStatus,
      String resultDataJson,
      String errorCode,
      String errorDetailsJson,
      UUID aggregateId,
      Integer aggregateVersion,
      UUID importId,
      UUID planItemId,
      Integer executionGeneration,
      String pendingReason,
      Instant createdAt,
      Instant completedAt,
      Instant updatedAt) {}
}
