package com.estoma.platformbff.contracts.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ResultEnvelope(
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
}
