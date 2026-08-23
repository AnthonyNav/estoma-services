package com.estoma.appointments.contracts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** V1 transport envelope; it is never used as a domain entity. */
public record MessageEnvelope(UUID messageId, String messageType, int messageVersion,
    MessageKind messageKind, String producer, Instant occurredAt, UUID correlationId,
    UUID causationId, UUID operationId, String idempotencyKey, UUID aggregateId,
    Integer aggregateVersion, WireActor actor, JsonNode payload) {}
