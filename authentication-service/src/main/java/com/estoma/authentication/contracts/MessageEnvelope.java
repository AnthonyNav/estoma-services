package com.estoma.authentication.contracts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The one physical envelope every durable command, result, and event
 * shares (messaging-architecture.md §8). A single type covers all three
 * {@link MessageKind}s. Routing chooses the destination, so the V1 envelope
 * deliberately has no {@code targetService} field.
 *
 * <p>{@code payload} is kept as a raw {@link JsonNode} because the concrete
 * shape varies by {@code messageType}; callers convert it to the specific
 * command/event payload record they expect.
 *
 * <p>Ephemeral security RPC (`platform.security.rpc` / Direct Reply-To)
 * does not use this envelope: system_contract.md's "RPC Security Rules"
 * explicitly forbid {@code operationId} and any durable Outbox/Inbox/DLQ
 * involvement for RPC. RPC requests and responses are the plain records
 * under {@code contracts.rpc}, correlated only by {@code correlationId}.
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
