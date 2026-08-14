package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedMessageRepository {

  Optional<ProcessedMessage> find(String consumerName, UUID messageId);

  boolean insertIfAbsent(ProcessedMessage message);

  int markApplied(String consumerName, UUID messageId, Instant appliedAt, Instant updatedAt);

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
