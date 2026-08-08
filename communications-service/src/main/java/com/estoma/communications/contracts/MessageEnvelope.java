package com.estoma.communications.contracts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The one physical envelope every durable command, result, and event
 * shares (messaging-architecture.md §8). A single type covers all three
 * {@link MessageKind}s -- fields that only apply to one kind ({@code
 * operationId} for COMMAND/RESULT) are simply null on the others.
 *
 * <p>{@code payload} is kept as a raw {@link JsonNode} because the concrete
 * shape varies by {@code messageType}; callers convert it to the specific
 * command/event payload record they expect.
 *
 * <p>Unlike {@code authentication-service}'s {@code MessageEnvelope}, this
 * one has no {@code targetService} field: messaging-architecture.md §8 is
 * explicit that the destination is determined entirely by RabbitMQ routing
 * (system_contract.md "Internal Architecture").
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
    UUID idempotencyKey,
    UUID aggregateId,
    Integer aggregateVersion,
    Actor actor,
    JsonNode payload) {}
