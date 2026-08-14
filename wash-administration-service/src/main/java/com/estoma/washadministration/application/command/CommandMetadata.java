package com.estoma.washadministration.application.command;

import java.time.Instant;
import java.util.UUID;

/** Metadata required by the broker-agnostic command dispatcher. */
public record CommandMetadata(
    UUID messageId,
    String operationType,
    int messageVersion,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String idempotencyKey,
    UUID aggregateId,
    Integer aggregateVersion,
    ActorContext actor) {}
