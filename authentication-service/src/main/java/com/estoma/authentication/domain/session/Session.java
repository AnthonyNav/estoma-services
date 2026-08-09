package com.estoma.authentication.domain.session;

import java.time.Instant;
import java.util.UUID;

/** A session is bounded by both idle and absolute expiry. */
public final class Session {

  public enum AuthState { NORMAL, PASSWORD_CHANGE_REQUIRED }
  public enum RefreshTokenMatch { CURRENT, PREVIOUS_WITHIN_GRACE, PREVIOUS_OUTSIDE_GRACE, NO_MATCH }

  private final UUID sessionId;
  private final UUID accountId;
  private final String refreshTokenHash;
  private final String previousRefreshTokenHash;
  private final Instant previousRefreshValidUntil;
  private final AuthState authState;
  private final String deviceName;
  private final String ipAddress;
  private final String userAgent;
  private final Instant createdAt;
  private final Instant idleExpiresAt;
  private final Instant absoluteExpiresAt;
  private final Instant lastUsedAt;
  private final Instant revokedAt;
  private final String revocationReason;

  private Session(UUID sessionId, UUID accountId, String refreshTokenHash, String previousRefreshTokenHash,
      Instant previousRefreshValidUntil, AuthState authState, String deviceName, String ipAddress, String userAgent,
      Instant createdAt, Instant idleExpiresAt, Instant absoluteExpiresAt, Instant lastUsedAt, Instant revokedAt,
      String revocationReason) {
    this.sessionId = requireNonNull(sessionId, "sessionId");
    this.accountId = requireNonNull(accountId, "accountId");
    this.refreshTokenHash = refreshTokenHash;
    this.previousRefreshTokenHash = previousRefreshTokenHash;
    this.previousRefreshValidUntil = previousRefreshValidUntil;
    this.authState = requireNonNull(authState, "authState");
    if (authState == AuthState.NORMAL && refreshTokenHash == null) throw new IllegalArgumentException("normal sessions require a refresh token");
    if (authState == AuthState.PASSWORD_CHANGE_REQUIRED && refreshTokenHash != null) throw new IllegalArgumentException("password-change sessions cannot have a refresh token");
    this.deviceName = deviceName;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.createdAt = requireNonNull(createdAt, "createdAt");
    this.idleExpiresAt = requireNonNull(idleExpiresAt, "idleExpiresAt");
    this.absoluteExpiresAt = requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
    if (!idleExpiresAt.isAfter(createdAt) || !absoluteExpiresAt.isAfter(createdAt)) throw new IllegalArgumentException("session expiry must be after createdAt");
    this.lastUsedAt = lastUsedAt;
    this.revokedAt = revokedAt;
    this.revocationReason = revocationReason;
  }

  public static Session createNormal(UUID sessionId, UUID accountId, String refreshTokenHash, String deviceName,
      String ipAddress, String userAgent, Instant now, Instant idleExpiresAt, Instant absoluteExpiresAt) {
    return new Session(sessionId, accountId, requireNonNull(refreshTokenHash, "refreshTokenHash"), null, null,
        AuthState.NORMAL, deviceName, ipAddress, userAgent, now, idleExpiresAt, absoluteExpiresAt, null, null, null);
  }

  public static Session createPasswordChangeRequired(UUID sessionId, UUID accountId, String deviceName, String ipAddress,
      String userAgent, Instant now, Instant expiresAt) {
    return new Session(sessionId, accountId, null, null, null, AuthState.PASSWORD_CHANGE_REQUIRED, deviceName,
        ipAddress, userAgent, now, expiresAt, expiresAt, null, null, null);
  }

  public static Session reconstitute(UUID sessionId, UUID accountId, String refreshTokenHash, String previousRefreshTokenHash,
      Instant previousRefreshValidUntil, AuthState authState, String deviceName, String ipAddress, String userAgent,
      Instant createdAt, Instant idleExpiresAt, Instant absoluteExpiresAt, Instant lastUsedAt, Instant revokedAt,
      String revocationReason) {
    return new Session(sessionId, accountId, refreshTokenHash, previousRefreshTokenHash, previousRefreshValidUntil,
        authState, deviceName, ipAddress, userAgent, createdAt, idleExpiresAt, absoluteExpiresAt, lastUsedAt, revokedAt,
        revocationReason);
  }

  public Session rotated(String newRefreshTokenHash, Instant now, Instant newIdleExpiresAt, Instant graceValidUntil) {
    return new Session(sessionId, accountId, requireNonNull(newRefreshTokenHash, "newRefreshTokenHash"), refreshTokenHash,
        graceValidUntil, AuthState.NORMAL, deviceName, ipAddress, userAgent, createdAt, newIdleExpiresAt,
        absoluteExpiresAt, now, revokedAt, revocationReason);
  }

  /** Completes a password change without preserving any prior refresh-token grace. */
  public Session passwordChanged(String newRefreshTokenHash, Instant now, Instant newIdleExpiresAt, Instant newAbsoluteExpiresAt) {
    return new Session(sessionId, accountId, requireNonNull(newRefreshTokenHash, "newRefreshTokenHash"), null, null,
        AuthState.NORMAL, deviceName, ipAddress, userAgent, createdAt, newIdleExpiresAt, newAbsoluteExpiresAt, now,
        revokedAt, revocationReason);
  }

  public Session revoked(String reason, Instant now) {
    return new Session(sessionId, accountId, refreshTokenHash, previousRefreshTokenHash, previousRefreshValidUntil,
        authState, deviceName, ipAddress, userAgent, createdAt, idleExpiresAt, absoluteExpiresAt, lastUsedAt, now, reason);
  }

  public boolean isValid(Instant now) { return revokedAt == null && idleExpiresAt.isAfter(now) && absoluteExpiresAt.isAfter(now); }
  public RefreshTokenMatch classifyRefreshTokenHash(String hash, Instant now) {
    if (refreshTokenHash != null && refreshTokenHash.equals(hash)) return RefreshTokenMatch.CURRENT;
    if (previousRefreshTokenHash == null || !previousRefreshTokenHash.equals(hash)) return RefreshTokenMatch.NO_MATCH;
    return previousRefreshValidUntil != null && previousRefreshValidUntil.isAfter(now)
        ? RefreshTokenMatch.PREVIOUS_WITHIN_GRACE
        : RefreshTokenMatch.PREVIOUS_OUTSIDE_GRACE;
  }
  public boolean matchesRefreshTokenHash(String hash, Instant now) {
    RefreshTokenMatch match = classifyRefreshTokenHash(hash, now);
    return match == RefreshTokenMatch.CURRENT || match == RefreshTokenMatch.PREVIOUS_WITHIN_GRACE;
  }
  public UUID sessionId() { return sessionId; }
  public UUID accountId() { return accountId; }
  public String refreshTokenHash() { return refreshTokenHash; }
  public String previousRefreshTokenHash() { return previousRefreshTokenHash; }
  public Instant previousRefreshValidUntil() { return previousRefreshValidUntil; }
  public AuthState authState() { return authState; }
  public String deviceName() { return deviceName; }
  public String ipAddress() { return ipAddress; }
  public String userAgent() { return userAgent; }
  public Instant createdAt() { return createdAt; }
  public Instant idleExpiresAt() { return idleExpiresAt; }
  public Instant absoluteExpiresAt() { return absoluteExpiresAt; }
  public Instant lastUsedAt() { return lastUsedAt; }
  public Instant revokedAt() { return revokedAt; }
  public String revocationReason() { return revocationReason; }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) throw new IllegalArgumentException(fieldName + " must not be null");
    return value;
  }
}
