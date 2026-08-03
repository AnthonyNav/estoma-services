package com.estoma.users.persistence;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of the Transactional Outbox. Implements {@link
 * OutboxPort} (the write side, used inside command-handler transactions)
 * plus the relay-facing claim/mark/release/purge API used by {@code
 * OutboxRelay} and {@code OutboxSweeper}.
 *
 * <p>Public — unlike the other repositories in this package, the extra
 * methods below are called directly (not through a port interface) by
 * {@code com.estoma.users.messaging.outbound} classes.
 *
 * <p>The claim query and its {@code FOR UPDATE SKIP LOCKED} multi-replica
 * strategy are described in full in docs/system-contract.md §8. {@code
 * available_at} is deliberately overloaded: it is a PENDING-row backoff
 * deadline and a PROCESSING-row lease deadline at the same time.
 */
@Repository
public class JdbcOutboxRepository implements OutboxPort {

  private final JdbcClient jdbc;

  JdbcOutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(OutboxAppendRequest request) {
    var occurredAt = Timestamps.toTimestamp(request.occurredAt());
    jdbc.sql(
            """
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind,
              aggregate_id, aggregate_version, correlation_id, causation_id,
              payload, metadata, status, attempts, occurred_at, available_at, published_at
            ) VALUES (
              :messageId, :messageType, :messageVersion, :messageKind,
              :aggregateId, :aggregateVersion, :correlationId, :causationId,
              CAST(:payload AS jsonb), CAST(:metadata AS jsonb),
              CAST('PENDING' AS outbox_status), 0, :occurredAt, :availableAt, NULL
            )
            """)
        .param("messageId", request.messageId())
        .param("messageType", request.messageType())
        .param("messageVersion", request.messageVersion())
        .param("messageKind", request.messageKind())
        .param("aggregateId", request.aggregateId())
        .param("aggregateVersion", request.aggregateVersion())
        .param("correlationId", request.correlationId())
        .param("causationId", request.causationId())
        .param("payload", request.payloadJson())
        .param("metadata", request.metadataJson())
        .param("occurredAt", occurredAt)
        .param("availableAt", occurredAt)
        .update();
  }

  /**
   * Claims up to {@code batchSize} PENDING rows whose {@code
   * available_at} has passed, atomically moving them to PROCESSING with a
   * lease expiring after {@code lease}, in a single statement. {@code
   * FOR UPDATE SKIP LOCKED} lets concurrent relay replicas claim disjoint
   * sets without blocking each other. {@code attempts} increments here —
   * at claim time, not at publish time — so a hard crash after claiming
   * still counts against the retry budget.
   */
  public List<OutboxRecord> claimBatch(int batchSize, Duration lease) {
    Instant now = Instant.now();
    var nowTs = Timestamps.toTimestamp(now);
    var leaseUntilTs = Timestamps.toTimestamp(now.plus(lease));

    return jdbc.sql(
            """
            WITH claimed AS (
              SELECT message_id FROM outbox_messages
              WHERE status = 'PENDING' AND available_at <= :now
              ORDER BY available_at, occurred_at
              FOR UPDATE SKIP LOCKED
              LIMIT :batchSize
            )
            UPDATE outbox_messages o
               SET status = 'PROCESSING',
                   attempts = o.attempts + 1,
                   available_at = :leaseUntil
              FROM claimed c
             WHERE o.message_id = c.message_id
            RETURNING
              o.message_id, o.message_type, o.message_version, o.message_kind,
              o.aggregate_id, o.aggregate_version, o.correlation_id, o.causation_id,
              o.payload::text AS payload, o.metadata::text AS metadata,
              o.status, o.attempts, o.occurred_at, o.available_at, o.published_at
            """)
        .param("now", nowTs)
        .param("batchSize", batchSize)
        .param("leaseUntil", leaseUntilTs)
        .query(OutboxRowMapper.INSTANCE)
        .list();
  }

  /** Marks successfully-published rows PUBLISHED. No-op for an empty list. */
  public int markPublished(List<UUID> messageIds) {
    if (messageIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            """
            UPDATE outbox_messages SET status = 'PUBLISHED', published_at = :now
            WHERE message_id IN (:ids) AND status = 'PROCESSING'
            """)
        .param("now", Timestamps.toTimestamp(Instant.now()))
        .param("ids", messageIds)
        .update();
  }

  /**
   * Returns claimed-but-failed-to-publish rows to PENDING immediately,
   * available for the next claim pass. {@code attempts} was already
   * incremented at claim time; this does not increment it again.
   */
  public int releaseForRetry(List<UUID> messageIds) {
    if (messageIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            """
            UPDATE outbox_messages SET status = 'PENDING', available_at = :now
            WHERE message_id IN (:ids) AND status = 'PROCESSING'
            """)
        .param("now", Timestamps.toTimestamp(Instant.now()))
        .param("ids", messageIds)
        .update();
  }

  /**
   * Returns PROCESSING rows whose lease has expired back to PENDING —
   * this is what protects against a relay instance that claimed a batch
   * and then crashed before marking it published or releasing it. This is
   * the "messages stuck in PROCESSING" recovery path
   * (docs/system-contract.md §8, §11).
   */
  public int releaseExpiredLeases() {
    return jdbc.sql(
            """
            UPDATE outbox_messages SET status = 'PENDING'
            WHERE status = 'PROCESSING' AND available_at < :now
            """)
        .param("now", Timestamps.toTimestamp(Instant.now()))
        .update();
  }

  /**
   * Marks PENDING rows that have exhausted their retry budget as FAILED,
   * so they stop being claimed. A human must intervene via the DLQ-style
   * runbook (docs/system-contract.md §15) — this method only stops the
   * bleeding, it does not alert.
   */
  public int failExhaustedPending(int maxAttempts) {
    return jdbc.sql(
            "UPDATE outbox_messages SET status = 'FAILED' WHERE status = 'PENDING' AND attempts >= :maxAttempts")
        .param("maxAttempts", maxAttempts)
        .update();
  }

  /** Deletes PUBLISHED rows older than {@code threshold} (retention). */
  public int purgePublishedOlderThan(Instant threshold) {
    return jdbc.sql("DELETE FROM outbox_messages WHERE status = 'PUBLISHED' AND published_at < :threshold")
        .param("threshold", Timestamps.toTimestamp(threshold))
        .update();
  }

  /** Count of rows currently PENDING or PROCESSING (used by outbox metrics). */
  public long countPendingOrProcessing() {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE status IN ('PENDING', 'PROCESSING')")
        .query(Long.class)
        .single();
  }

  /** Count of rows currently PROCESSING (used by the "stuck" metric). */
  public long countProcessing() {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE status = 'PROCESSING'")
        .query(Long.class)
        .single();
  }

  /** {@code occurred_at} of the oldest PENDING row, if any (used for the age gauge). */
  public Instant oldestPendingOccurredAt() {
    OffsetDateTime value =
        jdbc.sql("SELECT min(occurred_at) FROM outbox_messages WHERE status = 'PENDING'")
            .query(OffsetDateTime.class)
            .optional()
            .orElse(null);
    return value == null ? null : value.toInstant();
  }
}
