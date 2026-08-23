package com.estoma.washexecution.application.port;

import java.time.Instant;
import java.util.UUID;

public interface OutboxRepository {
  void append(OutboxMessage message);

  record OutboxMessage(
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
      String payloadJson) {}
}
