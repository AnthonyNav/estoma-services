package com.estoma.washexecution.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommandIdempotencyRepository {
  boolean insertIfAbsent(
      String producer,
      String operationType,
      String idempotencyKey,
      String requestFingerprint,
      Instant now);

  Optional<IdempotencyRecord> find(String producer, String operationType, String idempotencyKey);

  void markSucceeded(
      String producer,
      String operationType,
      String idempotencyKey,
      UUID aggregateId,
      int aggregateVersion,
      String resultJson,
      Instant now);

  void markRejected(
      String producer,
      String operationType,
      String idempotencyKey,
      UUID aggregateId,
      String errorCode,
      String resultJson,
      Instant now);

  record IdempotencyRecord(
      String requestFingerprint,
      String status,
      UUID aggregateId,
      Integer aggregateVersion,
      String resultJson,
      String errorCode) {}
}
