package com.estoma.authentication.contracts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The one physical envelope every V1 message shares. A single type covers
 * durable commands, results, events, and ephemeral security RPC.
 *
 * <p>{@code payload} is kept as a raw {@link JsonNode} because the concrete
 * shape varies by {@code messageType}; callers convert it to the specific
 * command/event payload record they expect.
 *
 * <p>Security RPC uses this envelope over Direct Reply-To, with its actor
 * identity in {@code actor}. RPC still forbids {@code operationId} and any
 * durable Outbox, Inbox, or DLQ involvement.
 */
public record MessageEnvelope(
    UUID messageId,
    String messageType,
    int messageVersion,
    MessageKind messageKind,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String idempotencyKey,
    UUID aggregateId,
    Integer aggregateVersion,
    Actor actor,
    JsonNode payload) {

}
