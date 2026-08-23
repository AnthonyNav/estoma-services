package com.estoma.washexecution.infrastructure.messaging.outbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRelayStore {
  List<RelayMessage> claimBatch(int limit, Instant now, UUID claimToken, Instant leaseUntil);
  boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt);
  boolean reschedule(UUID messageId, UUID claimToken, Instant availableAt, String error);
  boolean markFailed(UUID messageId, UUID claimToken, String error);

  record RelayMessage(
      UUID messageId,
      String messageType,
      int messageVersion,
      String messageKind,
      String producer,
      String routingKey,
      Instant occurredAt,
      UUID operationId,
      String idempotencyKey,
      UUID aggregateId,
      Integer aggregateVersion,
      UUID correlationId,
      UUID causationId,
      String actorJson,
      String payloadJson,
      int attemptCount) {}
}
