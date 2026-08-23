package com.estoma.appointments.application.model;

import java.time.Instant;
import java.util.UUID;

/** Intent serialized into the local transactional outbox. */
public record OutboxIntent(String messageType, String messageKind, UUID aggregateId,
    Long aggregateVersion, UUID correlationId, UUID causationId, String payloadJson,
    Instant occurredAt) {}
