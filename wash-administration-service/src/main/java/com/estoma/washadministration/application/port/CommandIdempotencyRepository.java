package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommandIdempotencyRepository {

  Optional<IdempotencyRecord> find(String producer, String operationType, String idempotencyKey);

  boolean insertIfAbsent(
      String producer,
      String operationType,
      String idempotencyKey,
      String requestFingerprint,
      Instant createdAt);

  int markSucceeded(
      String producer,
      String operationType,
      String idempotencyKey,
      UUID aggregateId,
      int aggregateVersion,
      String resultStatus,
      String resultDataJson,
      Instant completedAt);

  int markRejected(
      String producer,
      String operationType,
      String idempotencyKey,
      String errorCode,
      String resultStatus,
      String resultDataJson,
      Instant completedAt);

  int markFailed(
      String producer,
      String operationType,
      String idempotencyKey,
      String errorCode,
      String resultStatus,
      String resultDataJson,
      Instant completedAt);

  int markExpired(
      String producer,
      String operationType,
      String idempotencyKey,
      String errorCode,
      String resultStatus,
      String resultDataJson,
      Instant completedAt);

  record IdempotencyRecord(
      String producer,
      String operationType,
      String idempotencyKey,
      String requestFingerprint,
      String status,
      String resultStatus,
      String resultDataJson,
      String errorCode,
      UUID aggregateId,
      Integer aggregateVersion,
      Instant createdAt,
      Instant completedAt) {}
}
