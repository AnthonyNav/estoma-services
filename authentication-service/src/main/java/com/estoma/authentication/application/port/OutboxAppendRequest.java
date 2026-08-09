package com.estoma.authentication.application.port;

import com.estoma.authentication.contracts.Actor;
import java.time.Instant;
import java.util.UUID;

/**
 * What a command/RPC/projection handler passes to append one RESULT or
 * EVENT row to the Transactional Outbox, inside the same transaction as the
 * business change. {@code payloadJson} is pre-serialized JSON -- this port
 * does not depend on a JSON library.
 *
 * <p>Correlation, causation, operation, idempotency and actor context are
 * persisted as first-class metadata so the relay can reproduce the envelope.
 *
 * <p>{@code status = PENDING}, {@code attempts = 0}, and {@code availableAt
 * = occurredAt} are implicit: every newly-appended row starts immediately
 * eligible for a future relay to claim.
 */
public record OutboxAppendRequest(
    UUID messageId,
    String messageType,
    int messageVersion,
    String messageKind,
    UUID aggregateId,
    Integer aggregateVersion,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String idempotencyKey,
    Actor actor,
    String payloadJson,
    Instant occurredAt) {}
