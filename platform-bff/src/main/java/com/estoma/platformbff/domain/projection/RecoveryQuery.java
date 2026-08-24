package com.estoma.platformbff.domain.projection;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RecoveryQuery(
    UUID messageId,
    UUID recoveryJobId,
    long generation,
    String messageType,
    int messageVersion,
    String exchangeName,
    String routingKey,
    UUID correlationId,
    UUID causationId,
    String idempotencyKey,
    String actorJson,
    Map<String, Object> payload,
    String status,
    UUID claimToken,
    Instant claimUntil,
    int attemptCount,
    Instant nextAttemptAt,
    Instant publishedAt,
    String lastErrorCode,
    Instant createdAt,
    Instant updatedAt) {}
