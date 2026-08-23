package com.estoma.platformbff.domain.messaging;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(
    UUID messageId,
    String messageKind,
    String messageType,
    int messageVersion,
    String exchangeName,
    String routingKey,
    UUID operationId,
    UUID correlationId,
    UUID causationId,
    UUID aggregateId,
    Integer aggregateVersion,
    String idempotencyKey,
    String actorJson,
    String payloadJson,
    OutboxStatus status,
    UUID claimToken,
    Instant claimUntil,
    int attemptCount,
    Instant nextAttemptAt,
    Instant publishedAt,
    String lastErrorCode,
    Instant createdAt,
    Instant updatedAt) {
}
