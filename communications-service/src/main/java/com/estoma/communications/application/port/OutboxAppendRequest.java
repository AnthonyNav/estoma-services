package com.estoma.communications.application.port;

import com.estoma.communications.contracts.Actor;
import java.time.Instant;
import java.util.UUID;

/** Canonical durable message data persisted before RabbitMQ publication. */
public record OutboxAppendRequest(
    UUID messageId,
    String messageType,
    int messageVersion,
    String messageKind,
    UUID aggregateId,
    Integer aggregateVersion,
    UUID correlationId,
    UUID causationId,
    String payloadJson,
    Instant occurredAt,
    UUID operationId,
    String idempotencyKey,
    Actor actor,
    String destination) {

  /** Compatibility constructor for existing event-only producers. */
  public OutboxAppendRequest(
      UUID messageId,
      String messageType,
      int messageVersion,
      String messageKind,
      UUID aggregateId,
      Integer aggregateVersion,
      UUID correlationId,
      UUID causationId,
      String payloadJson,
      Instant occurredAt) {
    this(messageId, messageType, messageVersion, messageKind, aggregateId, aggregateVersion,
        correlationId, causationId, payloadJson, occurredAt, null, null, null, null);
  }
}
