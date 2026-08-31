package com.estoma.jornadas.contract;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record MessageEnvelope(
    UUID messageId,
    String messageType,
    int messageVersion,
    String messageKind,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String idempotencyKey,
    UUID aggregateId,
    Integer aggregateVersion,
    Actor actor,
    JsonNode payload) {}
