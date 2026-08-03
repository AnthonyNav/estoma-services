package com.estoma.users.contract;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The one physical envelope every command, result, and event shares
 * (messaging-architecture.md §8, docs/system-contract.md §2). A single
 * type covers all three {@link MessageKind}s — the fields that only apply
 * to one kind ({@code operationId}, {@code targetService} for COMMAND;
 * {@code operationId} for RESULT) are simply null on the others, and
 * {@link com.estoma.users.messaging.inbound.EnvelopeValidator} enforces
 * which fields are required for which kind, rather than the Java type
 * system.
 *
 * <p>{@code payload} is kept as a raw {@link JsonNode} because the
 * concrete shape varies by {@code messageType}; callers convert it to the
 * specific command/payload record they expect.
 *
 * <p>{@code operationId} is deliberately absent from EVENT messages this
 * service publishes (docs/system-contract.md §10, D6) — it tracks a
 * client-visible operation, which is meaningless to an event consumer.
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
    String targetService,
    UUID aggregateId,
    Integer aggregateVersion,
    Actor actor,
    JsonNode payload) {}
