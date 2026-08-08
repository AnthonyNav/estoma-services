package com.estoma.authentication.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

/** A row claimed by the Outbox Relay for publication (infra-internal -- never crosses the {@code application} boundary). */
public record OutboxRecord(
    UUID messageId,
    String messageType,
    int messageVersion,
    String messageKind,
    UUID aggregateId,
    Integer aggregateVersion,
    UUID correlationId,
    UUID causationId,
    String payloadJson,
    int attempts,
    Instant occurredAt) {}
