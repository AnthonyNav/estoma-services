package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {
  void append(OutboxMessage message);
  List<OutboxMessage> claimBatch(int limit, String workerId, Instant now, Instant leaseUntil);
  boolean markPublished(UUID messageId, String workerId, Instant publishedAt);
  boolean markFailed(UUID messageId, String workerId, String error, Instant nextAttemptAt);
  default boolean quarantine(UUID messageId, String workerId, String error, Instant at) {
    return markFailed(messageId, workerId, error, at);
  }
  record OutboxMessage(UUID messageId, String messageType, int messageVersion, String messageKind,
      UUID aggregateId, Long aggregateVersion, UUID correlationId, UUID causationId,
      String payloadJson, Instant occurredAt, Instant availableAt, String status,
      int attempts, String claimedBy, Instant leaseUntil) {}
}
