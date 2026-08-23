package com.estoma.washexecution.application.command;

import java.util.UUID;

public record CommandMetadata(
    UUID messageId,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String producer,
    String idempotencyKey,
    ActorContext actor) {}
