package com.estoma.users.contract;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The one physical envelope every command, query, result, and event
 * shares (messaging-architecture.md §8, docs/system-contract.md §2). A
 * single type covers all four {@link MessageKind}s — the fields that only
 * apply to one kind ({@code operationId} for COMMAND/QUERY/RESULT,
 * {@code idempotencyKey} for COMMAND) are simply null on the others, and
 * {@link com.estoma.users.messaging.inbound.EnvelopeValidator} enforces
 * which fields are required for which kind, rather than the Java type
 * system.
 *
 * <p>There is no {@code targetService} field: the platform V1 envelope
 * does not carry one (messaging-architecture.md §8: "No se mantiene
 * targetService") — the destination is determined entirely by RabbitMQ
 * routing (exchange/routing key), never re-derived from the envelope
 * itself.
 *
 * <p>{@code idempotencyKey} identifies a functional intention, distinct
 * from {@code messageId} (a concrete message instance, used for delivery
 * deduplication) and {@code operationId} (a client/BFF-observable
 * operation) — messaging-architecture.md §10.1. It protects a durable
 * effect against being applied twice when the same intention is resent
 * under a new {@code messageId}.
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
    String idempotencyKey,
    UUID aggregateId,
    Integer aggregateVersion,
    Actor actor,
    JsonNode payload) {}
