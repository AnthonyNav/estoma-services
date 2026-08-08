package com.estoma.authentication.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * What a command/RPC/projection handler passes to append one RESULT or
 * EVENT row to the Transactional Outbox, inside the same transaction as the
 * business change. {@code payloadJson} is pre-serialized JSON -- this port
 * does not depend on a JSON library.
 *
 * <p>No {@code metadata} column: this service's v1 Outbox schema is the
 * simple one decided for this tramo (system_contract.md "Outbox Operational
 * Policy"), without the {@code claim_token}/{@code lease_until}/{@code
 * metadata} deltas {@code users-service} added.
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
    String payloadJson,
    Instant occurredAt) {}
