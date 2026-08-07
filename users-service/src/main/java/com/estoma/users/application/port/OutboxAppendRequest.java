package com.estoma.users.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * What a command handler passes to append one RESULT or EVENT row to the
 * Transactional Outbox, inside the same transaction as the business
 * change (docs/system-contract.md §7).
 *
 * <p>{@code payloadJson}/{@code metadataJson} are pre-serialized JSON —
 * this port does not depend on any particular JSON library; the envelope
 * codec (added once the message contract exists) does the serialization
 * before calling {@link OutboxPort#append}.
 *
 * <p>{@code status = PENDING}, {@code attempts = 0}, and {@code
 * availableAt = occurredAt} are implicit: every newly-appended row starts
 * immediately eligible for the relay to claim.
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
    String metadataJson,
    Instant occurredAt) {}
