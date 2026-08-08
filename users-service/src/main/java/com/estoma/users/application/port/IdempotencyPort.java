package com.estoma.users.application.port;

import com.estoma.users.application.IdempotencyRecord;
import java.time.Instant;
import java.util.UUID;

/**
 * Functional idempotency for durable commands (commands.md §9,
 * messaging-architecture.md §10.1) — distinct from {@link InboxPort},
 * which only deduplicates delivery of one {@code messageId}. Keyed by
 * {@code (producer, idempotencyKey)}, so a retried intention is
 * recognized across a brand-new {@code messageId}.
 */
public interface IdempotencyPort {

  /**
   * Atomically claims {@code (producer, idempotencyKey)} for {@code
   * messageId} if unclaimed, or returns the existing claim otherwise —
   * free of TOCTOU races under concurrent redelivery (the implementation
   * must lock the row before returning it). Call {@link
   * IdempotencyRecord#isNewClaim(UUID)} with {@code messageId} to tell
   * the two cases apart.
   */
  IdempotencyRecord claim(
      String producer,
      String idempotencyKey,
      String messageType,
      UUID aggregateId,
      String requestFingerprint,
      UUID messageId,
      Instant now);

  /** Resolves a claim this caller owns to {@code SUCCEEDED}. */
  void markSucceeded(String producer, String idempotencyKey, int aggregateVersion, Instant completedAt);

  /** Resolves a claim this caller owns to {@code REJECTED}. */
  void markRejected(String producer, String idempotencyKey, String errorCode, Instant completedAt);
}
