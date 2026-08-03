package com.estoma.users.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * One claimed {@code outbox_messages} row, as returned by {@link
 * JdbcOutboxRepository#claimBatch}. This is what {@code OutboxRelay}
 * (messaging.outbound, added once AMQP publishing exists) reads to build
 * the wire message it publishes.
 *
 * <p>{@code payloadJson}/{@code metadataJson} are the raw {@code jsonb}
 * column contents as text — this package does not depend on Jackson or on
 * the envelope contract types; callers deserialize as needed.
 */
public record OutboxRecord(
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
    OutboxStatus status,
    int attempts,
    Instant occurredAt,
    Instant availableAt,
    Instant publishedAt) {}
