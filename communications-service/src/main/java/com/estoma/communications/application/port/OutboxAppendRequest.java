package com.estoma.communications.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * What a command/projection handler or the email delivery worker passes to
 * append one RESULT or EVENT row to the Transactional Outbox, inside the
 * same transaction as the business change. {@code payloadJson} is
 * pre-serialized JSON -- this port does not depend on a JSON library.
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
