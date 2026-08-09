package com.estoma.authentication.domain.recovery;

import java.time.Instant;
import java.util.UUID;

/**
 * A password recovery request (data-model.md §8): single-use, expiring,
 * attempt-limited. The attempt limit itself is a v1 configuration value
 * (system_contract.md "Password Recovery Parameters (v1)"), not domain law
 * -- this class only records attempts and lets the caller decide when the
 * limit is reached.
 */
public final class PasswordRecoveryRequest {

  private final UUID recoveryId;
  private final UUID accountId;
  private final String tokenHash;
  private final Instant createdAt;
  private final Instant expiresAt;
  private final Instant usedAt;
  private final Status status;
  private final Instant invalidatedAt;
  private final int failedAttempts;

  private PasswordRecoveryRequest(
      UUID recoveryId, UUID accountId, String tokenHash, Instant createdAt, Instant expiresAt, Instant usedAt,
      Status status, Instant invalidatedAt, int failedAttempts) {
    this.recoveryId = requireNonNull(recoveryId, "recoveryId");
    this.accountId = requireNonNull(accountId, "accountId");
    this.tokenHash = requireNonNull(tokenHash, "tokenHash");
    this.createdAt = requireNonNull(createdAt, "createdAt");
    this.expiresAt = requireNonNull(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("expiresAt must be after createdAt");
    }
    this.usedAt = usedAt;
    this.status = requireNonNull(status, "status");
    this.invalidatedAt = invalidatedAt;
    if (failedAttempts < 0) {
      throw new IllegalArgumentException("failedAttempts must not be negative");
    }
    this.failedAttempts = failedAttempts;
  }

  public static PasswordRecoveryRequest createNew(UUID recoveryId, UUID accountId, String tokenHash, Instant now, Instant expiresAt) {
    return new PasswordRecoveryRequest(recoveryId, accountId, tokenHash, now, expiresAt, null, Status.ACTIVE, null, 0);
  }

  public static PasswordRecoveryRequest reconstitute(
      UUID recoveryId, UUID accountId, String tokenHash, Instant createdAt, Instant expiresAt, Instant usedAt,
      Status status, Instant invalidatedAt, int failedAttempts) {
    return new PasswordRecoveryRequest(recoveryId, accountId, tokenHash, createdAt, expiresAt, usedAt, status, invalidatedAt, failedAttempts);
  }

  public PasswordRecoveryRequest attemptFailed() {
    Status nextStatus = failedAttempts + 1 >= 5 ? Status.ATTEMPTS_EXHAUSTED : status;
    return new PasswordRecoveryRequest(recoveryId, accountId, tokenHash, createdAt, expiresAt, usedAt, nextStatus, invalidatedAt, failedAttempts + 1);
  }

  /** {@code ResetPassword} (commands.md §8.5): consumes the request. */
  public PasswordRecoveryRequest used(Instant now) {
    return new PasswordRecoveryRequest(recoveryId, accountId, tokenHash, createdAt, expiresAt, now, Status.USED, invalidatedAt, failedAttempts);
  }

  public boolean isUsed() {
    return status == Status.USED;
  }

  public boolean isExpired(Instant now) {
    return !expiresAt.isAfter(now);
  }

  public boolean isActiveAndUsable(Instant now) {
    return status == Status.ACTIVE && !isExpired(now);
  }

  public UUID recoveryId() {
    return recoveryId;
  }

  public UUID accountId() {
    return accountId;
  }

  public String tokenHash() {
    return tokenHash;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant usedAt() {
    return usedAt;
  }

  public Status status() {
    return status;
  }

  public Instant invalidatedAt() {
    return invalidatedAt;
  }

  public int failedAttempts() {
    return failedAttempts;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }

  public enum Status {
    ACTIVE,
    INVALIDATED,
    USED,
    EXPIRED,
    ATTEMPTS_EXHAUSTED
  }
}
