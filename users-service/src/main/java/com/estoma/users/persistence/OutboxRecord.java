package com.estoma.users.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * One claimed {@code outbox_messages} row, as returned by {@link
 * JdbcOutboxRepository#claimBatch}. This is what {@code OutboxRelay}
 * reads to build the wire message it publishes.
 *
 * <p>{@code payloadJson}/{@code metadataJson} are the raw {@code jsonb}
 * column contents as text — this package does not depend on Jackson or on
 * the envelope contract types; callers deserialize as needed.
 *
 * <p>{@code claimToken}/{@code leaseUntil} are the fencing pair a caller
 * must present, unmodified, to {@code markPublished}/{@code
 * releaseForRetry}/{@code markFailed} — see docs/system-contract.md §8.
 * {@code lastErrorCode}/{@code lastErrorAt} reflect the previous failed
 * attempt, if any; both are NULL for a row that has never failed.
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
    UUID claimToken,
    Instant leaseUntil,
    String lastErrorCode,
    Instant lastErrorAt,
    Instant occurredAt,
    Instant availableAt,
    Instant publishedAt) {}
