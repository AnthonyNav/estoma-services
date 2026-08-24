package com.estoma.platformbff.contracts.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record RpcRequestEnvelope(
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

  public static RpcRequestEnvelope create(
      String operation,
      UUID correlationId,
      String idempotencyKey,
      UUID targetAccountId,
      ActorContext actor,
      JsonNode payload) {
    return new RpcRequestEnvelope(
        UUID.randomUUID(), operation, 1, MessageKind.RPC_REQUEST, "bff", Instant.now(),
        correlationId, null, null, idempotencyKey, targetAccountId, null, actor, payload);
  }
}
