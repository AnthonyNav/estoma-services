package com.estoma.users.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code idempotency_records} — the claim (or pre-existing
 * claim) {@link com.estoma.users.application.port.IdempotencyPort#claim}
 * returns. {@code aggregateVersion}/{@code errorCode} are only populated
 * once {@code terminalStatus} leaves {@link IdempotencyStatus#IN_PROGRESS}.
 */
public record IdempotencyRecord(
    String producer,
    String idempotencyKey,
    String messageType,
    UUID aggregateId,
    String requestFingerprint,
    UUID claimedByMessageId,
    IdempotencyStatus terminalStatus,
    Integer aggregateVersion,
    String errorCode,
    Instant createdAt,
    Instant completedAt) {

  /** Whether {@code messageId} is the one that inserted this row — i.e. a genuinely new intention. */
  public boolean isNewClaim(UUID messageId) {
    return claimedByMessageId.equals(messageId);
  }

  /** Whether {@code messageType}/{@code fingerprint} match this claim's original intention. */
  public boolean matchesIntention(String messageType, String fingerprint) {
    return this.messageType.equals(messageType) && this.requestFingerprint.equals(fingerprint);
  }
}
