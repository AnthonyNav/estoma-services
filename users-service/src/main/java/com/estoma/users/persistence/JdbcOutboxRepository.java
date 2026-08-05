package com.estoma.users.persistence;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import com.estoma.users.messaging.outbound.OutboxFailureCode;
import java.time.Clock;
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
 * strategy, plus the fencing scheme (claim_token/lease_until), are
 * described in full in docs/system-contract.md §8. {@code available_at}
 * means only "the next instant a PENDING row may be reclaimed" —
 * {@code lease_until} is the separate PROCESSING lease deadline.
 *
 * <p>Every transition out of PROCESSING ({@link #markPublished}, {@link
 * #releaseForRetry}, {@link #markFailed}) requires the exact {@code
 * claimToken} the row was claimed with; if the row's claim was already
 * reclaimed by another worker (lease expired, sweeper released it), the
 * update affects zero rows and the caller must not retry the transition
 * without a fresh claim (docs/system-contract.md §8, Round 1 fencing).
 */
@Repository
public class JdbcOutboxRepository implements OutboxPort {

  private final JdbcClient jdbc;
  private final Clock clock;

  JdbcOutboxRepository(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
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
   * fresh {@code claimToken} and a lease expiring after {@code lease}, in
   * a single statement. {@code FOR UPDATE SKIP LOCKED} lets concurrent
   * relay replicas claim disjoint sets without blocking each other.
   * {@code attempts} increments here — at claim time, not at publish time
   * — so a hard crash after claiming still counts against the retry
   * budget. {@code available_at} is left untouched: it is not the lease.
   *
   * <p>One {@code claimToken} is shared by the whole batch — safe because
   * every transition also filters by {@code message_id}.
   */
  public List<OutboxRecord> claimBatch(int batchSize, Duration lease) {
    Instant now = clock.instant();
    UUID claimToken = UUID.randomUUID();
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
                   claim_token = :claimToken,
                   lease_until = :leaseUntil
              FROM claimed c
             WHERE o.message_id = c.message_id
            RETURNING
              o.message_id, o.message_type, o.message_version, o.message_kind,
              o.aggregate_id, o.aggregate_version, o.correlation_id, o.causation_id,
              o.payload::text AS payload, o.metadata::text AS metadata,
              o.status, o.attempts, o.claim_token, o.lease_until,
              o.last_error_code, o.last_error_at,
              o.occurred_at, o.available_at, o.published_at
            """)
        .param("now", nowTs)
        .param("batchSize", batchSize)
        .param("claimToken", claimToken)
        .param("leaseUntil", leaseUntilTs)
        .query(OutboxRowMapper.INSTANCE)
        .list();
  }

  /**
   * Marks a claimed row PUBLISHED, but only if {@code claimToken} still
   * matches the row's current claim. Zero rows affected means this
   * worker's claim was already reclaimed by someone else — the caller
   * must not retry with the same token.
   */
  public int markPublished(UUID messageId, UUID claimToken, Instant publishedAt) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
               SET status = 'PUBLISHED',
                   published_at = :publishedAt,
                   claim_token = NULL,
                   lease_until = NULL,
                   last_error_code = NULL,
                   last_error_at = NULL
             WHERE message_id = :messageId AND status = 'PROCESSING' AND claim_token = :claimToken
            """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("publishedAt", Timestamps.toTimestamp(publishedAt))
        .update();
  }

  /**
   * Returns a claimed-but-failed-to-publish row to PENDING at {@code
   * nextAvailableAt} (the caller has already applied backoff), fenced by
   * {@code claimToken} exactly like {@link #markPublished}. {@code
   * attempts} was already incremented at claim time; this does not
   * increment it again. Records the sanitized failure reason.
   */
  public int releaseForRetry(UUID messageId, UUID claimToken, Instant nextAvailableAt, OutboxFailureCode errorCode) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
               SET status = 'PENDING',
                   available_at = :nextAvailableAt,
                   claim_token = NULL,
                   lease_until = NULL,
                   last_error_code = :errorCode,
                   last_error_at = :now
             WHERE message_id = :messageId AND status = 'PROCESSING' AND claim_token = :claimToken
            """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("nextAvailableAt", Timestamps.toTimestamp(nextAvailableAt))
        .param("errorCode", errorCode.name())
        .param("now", Timestamps.toTimestamp(clock.instant()))
        .update();
  }

  /**
   * Marks a claimed row FAILED — its retry budget is exhausted. Fenced by
   * {@code claimToken} exactly like {@link #markPublished}. A human must
   * intervene via the runbook (docs/system-contract.md §15); this method
   * only stops the bleeding, it does not alert.
   */
  public int markFailed(UUID messageId, UUID claimToken, OutboxFailureCode errorCode) {
    return jdbc.sql(
            """
            UPDATE outbox_messages
               SET status = 'FAILED',
                   claim_token = NULL,
                   lease_until = NULL,
                   last_error_code = :errorCode,
                   last_error_at = :now
             WHERE message_id = :messageId AND status = 'PROCESSING' AND claim_token = :claimToken
            """)
        .param("messageId", messageId)
        .param("claimToken", claimToken)
        .param("errorCode", errorCode.name())
        .param("now", Timestamps.toTimestamp(clock.instant()))
        .update();
  }

  /**
   * PROCESSING rows whose lease has already expired — a relay instance
   * that claimed a batch and then crashed, was interrupted, or otherwise
   * never reached a mark/release call. This is the "messages stuck in
   * PROCESSING" recovery path (docs/system-contract.md §8, §11); {@code
   * OutboxSweeper} reclaims each one individually through the same fenced
   * transitions the relay uses.
   */
  public List<ExpiredLease> findExpiredLeases(int limit) {
    return jdbc.sql(
            """
            SELECT message_id, claim_token, attempts FROM outbox_messages
            WHERE status = 'PROCESSING' AND lease_until < :now
            ORDER BY lease_until
            LIMIT :limit
            """)
        .param("now", Timestamps.toTimestamp(clock.instant()))
        .param("limit", limit)
        .query((rs, rowNum) -> new ExpiredLease(
            rs.getObject("message_id", UUID.class), rs.getObject("claim_token", UUID.class), rs.getInt("attempts")))
        .list();
  }

  /**
   * Marks PENDING rows that have exhausted their retry budget as FAILED,
   * so they stop being claimed. Kept as a defensive backstop — the
   * primary transition to FAILED happens inline when a publish attempt
   * fails with {@code attempts >= maxAttempts} (see {@code OutboxRelay}).
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

  /** Count of rows currently PENDING only (used by the outbox pending gauge). */
  public long countPending() {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE status = 'PENDING'").query(Long.class).single();
  }

  /**
   * Count of PROCESSING rows whose lease has already expired — the
   * "stuck in PROCESSING" metric. Deliberately not every PROCESSING row:
   * one mid-flight in a live relay batch is normal, not stuck
   * (docs/system-contract.md §8, §11).
   */
  public long countStuckProcessing() {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE status = 'PROCESSING' AND lease_until < :now")
        .param("now", Timestamps.toTimestamp(clock.instant()))
        .query(Long.class)
        .single();
  }

  /**
   * {@code available_at} of the oldest PENDING row that is already
   * reclaimable ({@code available_at <= now}), if any. Used for the
   * relay-stall health check and age gauge — measuring from this instant
   * rather than from {@code occurred_at} means a row that just finished a
   * long, legitimate backoff is not mistaken for a stalled relay
   * (docs/system-contract.md §8, §11).
   */
  public Instant oldestOverduePendingAvailableAt() {
    Instant now = clock.instant();
    OffsetDateTime value =
        jdbc.sql("SELECT min(available_at) FROM outbox_messages WHERE status = 'PENDING' AND available_at <= :now")
            .param("now", Timestamps.toTimestamp(now))
            .query(OffsetDateTime.class)
            .optional()
            .orElse(null);
    return value == null ? null : value.toInstant();
  }

  /**
   * {@code lease_until} of the PROCESSING row whose lease expired
   * longest ago, if any. Paired with {@link #oldestOverduePendingAvailableAt()}
   * to drive the relay-stall health check.
   */
  public Instant oldestExpiredLeaseUntil() {
    Instant now = clock.instant();
    OffsetDateTime value =
        jdbc.sql("SELECT min(lease_until) FROM outbox_messages WHERE status = 'PROCESSING' AND lease_until < :now")
            .param("now", Timestamps.toTimestamp(now))
            .query(OffsetDateTime.class)
            .optional()
            .orElse(null);
    return value == null ? null : value.toInstant();
  }
}
