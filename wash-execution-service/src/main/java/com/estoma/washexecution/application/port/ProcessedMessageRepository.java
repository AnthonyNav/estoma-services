package com.estoma.washexecution.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedMessageRepository {
  enum Status { DEFERRED, APPLIED }

  record ProcessedMessage(
      String consumerName,
      UUID messageId,
      Status status,
      String messageType,
      int messageVersion,
      String messageKind,
      String producer,
      UUID correlationId,
      UUID causationId,
      UUID operationId,
      String idempotencyKey,
      UUID aggregateId,
      Long aggregateVersion,
      String actorJson,
      String payloadJson,
      Instant receivedAt,
      Instant occurredAt,
      Instant appliedAt,
      String deferredReason,
      Instant updatedAt) {}

  boolean insertIfAbsent(ProcessedMessage message);
  Optional<ProcessedMessage> find(String consumerName, UUID messageId);
  boolean claimDeferred(String consumerName, UUID messageId, Instant now, UUID claimToken, Instant leaseUntil);
  List<ProcessedMessage> claimDeferred(String consumerName, int limit, Instant now, UUID claimToken, Instant leaseUntil);
  boolean markApplied(String consumerName, UUID messageId, UUID claimToken, Instant appliedAt);
  boolean keepDeferred(String consumerName, UUID messageId, UUID claimToken, String reason, Instant updatedAt);
}
