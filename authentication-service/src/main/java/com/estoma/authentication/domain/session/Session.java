package com.estoma.authentication.domain.session;

import java.time.Instant;
import java.util.UUID;

/**
 * A renewable session (data-model.md §7). Valid when {@code revokedAt} is
 * null and {@code expiresAt} is in the future -- account/person activity is
 * checked by the caller, not encoded here, since that requires reading
 * other aggregates.
 */
public final class Session {

  private final UUID sessionId;
  private final UUID accountId;
  private final String refreshTokenHash;
  private final String deviceName;
  private final String ipAddress;
  private final String userAgent;
  private final Instant createdAt;
  private final Instant expiresAt;
  private final Instant lastUsedAt;
  private final Instant revokedAt;
  private final String revocationReason;

  private Session(
      UUID sessionId,
      UUID accountId,
      String refreshTokenHash,
      String deviceName,
      String ipAddress,
      String userAgent,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt,
      String revocationReason) {
    this.sessionId = requireNonNull(sessionId, "sessionId");
    this.accountId = requireNonNull(accountId, "accountId");
    this.refreshTokenHash = requireNonNull(refreshTokenHash, "refreshTokenHash");
    this.deviceName = deviceName;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.createdAt = requireNonNull(createdAt, "createdAt");
    this.expiresAt = requireNonNull(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("expiresAt must be after createdAt");
    }
    this.lastUsedAt = lastUsedAt;
    this.revokedAt = revokedAt;
    this.revocationReason = revocationReason;
  }

  public static Session createNew(
      UUID sessionId,
      UUID accountId,
      String refreshTokenHash,
      String deviceName,
      String ipAddress,
      String userAgent,
      Instant now,
      Instant expiresAt) {
    return new Session(sessionId, accountId, refreshTokenHash, deviceName, ipAddress, userAgent, now, expiresAt, null, null, null);
  }

  public static Session reconstitute(
      UUID sessionId,
      UUID accountId,
      String refreshTokenHash,
      String deviceName,
      String ipAddress,
      String userAgent,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt,
      String revocationReason) {
    return new Session(
        sessionId, accountId, refreshTokenHash, deviceName, ipAddress, userAgent, createdAt, expiresAt, lastUsedAt,
        revokedAt, revocationReason);
  }

  /** {@code RefreshSession} (commands.md §8.2): rotate-on-use, invalidating the previous token. */
  public Session rotated(String newRefreshTokenHash, Instant now, Instant newExpiresAt) {
    return new Session(
        sessionId, accountId, newRefreshTokenHash, deviceName, ipAddress, userAgent, createdAt, newExpiresAt, now,
        revokedAt, revocationReason);
  }

  /** {@code RevokeSession} (commands.md §8.3) or {@code RevokeAllAccountSessions} (commands.md §5.1). */
  public Session revoked(String reason, Instant now) {
    return new Session(
        sessionId, accountId, refreshTokenHash, deviceName, ipAddress, userAgent, createdAt, expiresAt, lastUsedAt,
        now, reason);
  }

  public boolean isValid(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  public UUID sessionId() {
    return sessionId;
  }

  public UUID accountId() {
    return accountId;
  }

  public String refreshTokenHash() {
    return refreshTokenHash;
  }

  public String deviceName() {
    return deviceName;
  }

  public String ipAddress() {
    return ipAddress;
  }

  public String userAgent() {
    return userAgent;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant lastUsedAt() {
    return lastUsedAt;
  }

  public Instant revokedAt() {
    return revokedAt;
  }

  public String revocationReason() {
    return revocationReason;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }
}
