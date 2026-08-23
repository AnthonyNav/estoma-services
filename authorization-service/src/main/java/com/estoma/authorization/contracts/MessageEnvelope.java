package com.estoma.authorization.contracts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Transport-neutral representation of a validated V1 envelope. */
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
    WireActor actor,
    JsonNode payload) {}
