package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedMessageRepository {

  Optional<ProcessedMessage> find(String consumerName, UUID messageId);

  boolean insertIfAbsent(ProcessedMessage message);

  int markApplied(String consumerName, UUID messageId, Instant appliedAt, Instant updatedAt);

  /** Updates metadata of an already deferred entry; it must never reopen APPLIED messages. */
  int updateDeferredReason(
      String consumerName, UUID messageId, String deferredReason, Instant updatedAt);

  /** Compatibility name for application code; semantics remain DEFERRED -> DEFERRED only. */
  default int markDeferred(
      String consumerName, UUID messageId, String deferredReason, Instant updatedAt) {
    return updateDeferredReason(consumerName, messageId, deferredReason, updatedAt);
  }

  boolean hasDeferredNewerThan(String consumerName, UUID aggregateId, int aggregateVersion);

  List<ProcessedMessage> findDeferredByAggregate(
      String consumerName, UUID aggregateId, int limit);

  List<ProcessedMessage> findDeferredCommandsByAccountId(
      String consumerName, UUID accountId, int limit);

  List<ProcessedMessage> findDeferred(String consumerName, String messageKind, int limit);

  record ProcessedMessage(
      String consumerName,
      UUID messageId,
      String status,
      String messageType,
      int messageVersion,
      String messageKind,
      String producer,
      UUID correlationId,
      UUID causationId,
      UUID operationId,
      String idempotencyKey,
      UUID aggregateId,
      Integer aggregateVersion,
      ActorRef actor,
      String payloadJson,
      Instant receivedAt,
      Instant occurredAt,
      Instant appliedAt,
      String deferredReason,
      Instant updatedAt) {}
}
