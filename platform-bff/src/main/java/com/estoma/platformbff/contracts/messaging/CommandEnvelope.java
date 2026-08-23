package com.estoma.platformbff.contracts.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CommandEnvelope(
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
    ActorContext actor,
    JsonNode payload) {

  public static CommandEnvelope create(String messageType, int messageVersion, UUID operationId,
      UUID correlationId, String idempotencyKey, UUID aggregateId, Integer aggregateVersion,
      ActorContext actor, JsonNode payload) {
    return new CommandEnvelope(UUID.randomUUID(), messageType, messageVersion, MessageKind.COMMAND,
        "bff", Instant.now(), correlationId, null, operationId, idempotencyKey, aggregateId,
        aggregateVersion, actor, payload);
  }
}
