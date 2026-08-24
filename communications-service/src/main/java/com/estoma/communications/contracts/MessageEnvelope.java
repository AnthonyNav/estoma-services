package com.estoma.communications.contracts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Canonical V1 durable messaging envelope used by Communications. */
public record MessageEnvelope(
    UUID messageId,
    String messageType,
    int messageVersion,
    MessageKind messageKind,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String idempotencyKey,
    UUID aggregateId,
    Integer aggregateVersion,
    Actor actor,
    JsonNode payload) {

  /** Source-compatible bridge for older in-process fixtures/adapters; wire type remains String. */
  public MessageEnvelope(
      UUID messageId,
      String messageType,
      int messageVersion,
      MessageKind messageKind,
      String producer,
      Instant occurredAt,
      UUID correlationId,
      UUID causationId,
      UUID operationId,
      UUID idempotencyKey,
      UUID aggregateId,
      Integer aggregateVersion,
      Actor actor,
      JsonNode payload) {
    this(messageId, messageType, messageVersion, messageKind, producer, occurredAt, correlationId,
        causationId, operationId, idempotencyKey == null ? null : idempotencyKey.toString(),
        aggregateId, aggregateVersion, actor, payload);
  }
}
