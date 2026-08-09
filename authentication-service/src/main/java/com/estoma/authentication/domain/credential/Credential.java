package com.estoma.authentication.domain.credential;

import java.time.Instant;
import java.util.UUID;

/**
 * 0..1 with {@link com.estoma.authentication.domain.account.Account}: the
 * row does not exist until a credential is configured through the
 * ephemeral security RPC flow, never through {@code CreateAccount} or any
 * durable command (system_contract.md "Mandatory Invariants";
 * data-model.md §6). A separate aggregate from {@code Account} -- this
 * service's persistence boundary keeps them in separate tables, and
 * {@code ActivateAccount}'s "credential already configured" precondition
 * is cross-aggregate, checked by the handler via {@code
 * CredentialRepository.exists}, not by either aggregate itself.
 */
public final class Credential {

  private final UUID accountId;
  private final PasswordHash hash;
  private final Instant passwordChangedAt;
  private final boolean mustChangePassword;
  private final Instant temporaryPasswordExpiresAt;
  private final Instant temporaryPasswordConsumedAt;
  private final int credentialVersion;

  private Credential(UUID accountId, PasswordHash hash, Instant passwordChangedAt, boolean mustChangePassword,
      Instant temporaryPasswordExpiresAt, Instant temporaryPasswordConsumedAt, int credentialVersion) {
    this.accountId = requireNonNull(accountId, "accountId");
    this.hash = requireNonNull(hash, "hash");
    this.passwordChangedAt = requireNonNull(passwordChangedAt, "passwordChangedAt");
    this.mustChangePassword = mustChangePassword;
    this.temporaryPasswordExpiresAt = temporaryPasswordExpiresAt;
    this.temporaryPasswordConsumedAt = temporaryPasswordConsumedAt;
    if (credentialVersion <= 0) {
      throw new IllegalArgumentException("credentialVersion must be positive");
    }
    this.credentialVersion = credentialVersion;
  }

  /** First-time configuration (SetTemporaryPassword, or a future self-service activation flow). */
  public static Credential configure(UUID accountId, PasswordHash hash, Instant now) {
    return new Credential(accountId, hash, now, false, null, null, 1);
  }

  public static Credential configureTemporary(UUID accountId, PasswordHash hash, Instant expiresAt, Instant now) {
    if (!expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("temporaryPasswordExpiresAt must be after now");
    }
    return new Credential(accountId, hash, now, true, expiresAt, null, 1);
  }

  /** Reconstructs a Credential from a persisted row. */
  public static Credential reconstitute(UUID accountId, PasswordHash hash, Instant passwordChangedAt,
      boolean mustChangePassword, Instant temporaryPasswordExpiresAt, Instant temporaryPasswordConsumedAt,
      int credentialVersion) {
    return new Credential(accountId, hash, passwordChangedAt, mustChangePassword, temporaryPasswordExpiresAt,
        temporaryPasswordConsumedAt, credentialVersion);
  }

  /** {@code ChangePassword}/{@code ResetPassword}/{@code RegenerateTemporaryPassword}: a full rehash. */
  public Credential changed(PasswordHash newHash, Instant now) {
    return new Credential(accountId, newHash, now, false, null, null, credentialVersion + 1);
  }

  public Credential temporaryPassword(PasswordHash newHash, Instant expiresAt, Instant now) {
    if (!expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("temporaryPasswordExpiresAt must be after now");
    }
    return new Credential(accountId, newHash, now, true, expiresAt, null, credentialVersion + 1);
  }

  public boolean isValidTemporaryPassword(Instant now) {
    return mustChangePassword && temporaryPasswordConsumedAt == null && temporaryPasswordExpiresAt != null
        && temporaryPasswordExpiresAt.isAfter(now);
  }

  public UUID accountId() {
    return accountId;
  }

  public PasswordHash hash() {
    return hash;
  }

  public Instant passwordChangedAt() {
    return passwordChangedAt;
  }

  public boolean mustChangePassword() { return mustChangePassword; }
  public Instant temporaryPasswordExpiresAt() { return temporaryPasswordExpiresAt; }
  public Instant temporaryPasswordConsumedAt() { return temporaryPasswordConsumedAt; }
  public int credentialVersion() { return credentialVersion; }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }
}
