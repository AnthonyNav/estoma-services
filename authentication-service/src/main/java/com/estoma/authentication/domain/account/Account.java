package com.estoma.authentication.domain.account;

import com.estoma.authentication.contracts.AccountStatus;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import java.time.Instant;
import java.util.UUID;

/**
 * The aggregate root this service owns: an independent access identity
 * belonging to a logical {@code personId} (system_contract.md "Ownership").
 * Immutable by design, mirroring {@code users-service}'s {@code Person}:
 * every transition returns a new instance; the guarded SQL update lives in
 * {@code JdbcAccountRepository}.
 *
 * <p>{@code Credential} is a separate aggregate (0..1 with this one, its
 * own table). {@link #activated} does not check that a credential exists --
 * that precondition is cross-aggregate and is checked by the command
 * handler before calling this method (system_contract.md "Mandatory
 * Invariants": "ActivateAccount requires a configured credential").
 *
 * <p>Failed-attempt/lockout thresholds are v1 configuration values
 * (system_contract.md "Lockout Policy (v1)"), not domain law: this class
 * accepts a caller-computed {@code lockedUntil} rather than hard-coding the
 * attempt limit or lock duration itself.
 */
public final class Account {

  private final UUID accountId;
  private final UUID personId;
  private final LoginIdentifier loginIdentifier;
  private final AccountStatus status;
  private final int failedAttempts;
  private final Instant lockedUntil;
  private final boolean mustChangePassword;
  private final Instant temporaryPasswordExpiresAt;
  private final int version;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final Instant deactivatedAt;

  private Account(
      UUID accountId,
      UUID personId,
      LoginIdentifier loginIdentifier,
      AccountStatus status,
      int failedAttempts,
      Instant lockedUntil,
      boolean mustChangePassword,
      Instant temporaryPasswordExpiresAt,
      int version,
      Instant createdAt,
      Instant updatedAt,
      Instant deactivatedAt) {
    this.accountId = requireNonNull(accountId, "accountId");
    this.personId = requireNonNull(personId, "personId");
    this.loginIdentifier = requireNonNull(loginIdentifier, "loginIdentifier");
    this.status = requireNonNull(status, "status");
    if (failedAttempts < 0) {
      throw new IllegalArgumentException("failedAttempts must not be negative");
    }
    this.failedAttempts = failedAttempts;
    this.lockedUntil = lockedUntil;
    this.mustChangePassword = mustChangePassword;
    this.temporaryPasswordExpiresAt = temporaryPasswordExpiresAt;
    if (version <= 0) {
      throw new IllegalArgumentException("version must be positive");
    }
    this.version = version;
    this.createdAt = requireNonNull(createdAt, "createdAt");
    this.updatedAt = requireNonNull(updatedAt, "updatedAt");
    this.deactivatedAt = deactivatedAt;
  }

  /** A brand-new account: version 1, INACTIVE, no credential, no lock. */
  public static Account createNew(UUID accountId, UUID personId, LoginIdentifier loginIdentifier, Instant now) {
    return new Account(accountId, personId, loginIdentifier, AccountStatus.INACTIVE, 0, null, false, null, 1, now, now, null);
  }

  /** Reconstructs an Account from a persisted row. No invariant re-derivation beyond the constructor's own checks. */
  public static Account reconstitute(
      UUID accountId,
      UUID personId,
      LoginIdentifier loginIdentifier,
      AccountStatus status,
      int failedAttempts,
      Instant lockedUntil,
      boolean mustChangePassword,
      Instant temporaryPasswordExpiresAt,
      int version,
      Instant createdAt,
      Instant updatedAt,
      Instant deactivatedAt) {
    return new Account(
        accountId,
        personId,
        loginIdentifier,
        status,
        failedAttempts,
        lockedUntil,
        mustChangePassword,
        temporaryPasswordExpiresAt,
        version,
        createdAt,
        updatedAt,
        deactivatedAt);
  }

  /**
   * {@code ActivateAccount} (commands.md §4.2). The caller must already
   * have verified a credential exists; this only enforces the account's own
   * state (INACTIVE -&gt; ACTIVE).
   */
  public Account activated(Instant now) {
    if (status == AccountStatus.ACTIVE) {
      throw new AuthenticationRejectedException(RejectionCode.ACCOUNT_ALREADY_ACTIVE, "account " + accountId + " is already active");
    }
    return new Account(
        accountId, personId, loginIdentifier, AccountStatus.ACTIVE, failedAttempts, lockedUntil, mustChangePassword,
        temporaryPasswordExpiresAt, version + 1, createdAt, now, deactivatedAt);
  }

  /**
   * {@code DeactivateAccount} (commands.md §4.3). Unlike {@link
   * #activated}, there is no {@code ACCOUNT_ALREADY_INACTIVE} rejection
   * code in commands.md §3 -- a stale/duplicate call is instead caught by
   * the repository's guarded update (0 rows affected -&gt;
   * {@code ACCOUNT_VERSION_CONFLICT}), the same disambiguation
   * {@code users-service} uses for its own status transitions.
   */
  public Account deactivated(Instant now) {
    return new Account(
        accountId, personId, loginIdentifier, AccountStatus.INACTIVE, failedAttempts, lockedUntil, mustChangePassword,
        temporaryPasswordExpiresAt, version + 1, createdAt, now, now);
  }

  /** {@code ChangeLoginIdentifier} (commands.md §4.4). */
  public Account withLoginIdentifierChanged(LoginIdentifier newLoginIdentifier, Instant now) {
    return new Account(
        accountId, personId, newLoginIdentifier, status, failedAttempts, lockedUntil, mustChangePassword,
        temporaryPasswordExpiresAt, version + 1, createdAt, now, deactivatedAt);
  }

  /**
   * A failed authentication attempt (commands.md §8.1). {@code
   * lockedUntilOrNull} is computed by the caller from the v1 lockout policy
   * (system_contract.md "Lockout Policy (v1)"); this method does not decide
   * when to lock, only records what the caller decided.
   */
  public Account withFailedAttemptRecorded(Instant now, Instant lockedUntilOrNull) {
    return new Account(
        accountId, personId, loginIdentifier, status, failedAttempts + 1, lockedUntilOrNull, mustChangePassword,
        temporaryPasswordExpiresAt, version, createdAt, now, deactivatedAt);
  }

  /** A successful authentication (data-model.md §10): resets attempts and clears any lock. */
  public Account withAttemptsReset(Instant now) {
    return new Account(
        accountId, personId, loginIdentifier, status, 0, null, mustChangePassword, temporaryPasswordExpiresAt,
        version, createdAt, now, deactivatedAt);
  }

  /** {@code SetTemporaryPassword}/{@code RegenerateTemporaryPassword} (commands.md §7). */
  public Account withTemporaryPasswordSet(Instant expiresAt, Instant now) {
    return new Account(
        accountId, personId, loginIdentifier, status, failedAttempts, lockedUntil, true, expiresAt, version,
        createdAt, now, deactivatedAt);
  }

  /** {@code ChangePassword}/{@code ResetPassword} (commands.md §8.5, §8.6): clears the temporary-password state. */
  public Account withPasswordChangeCompleted(Instant now) {
    return new Account(
        accountId, personId, loginIdentifier, status, failedAttempts, lockedUntil, false, null, version, createdAt,
        now, deactivatedAt);
  }

  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  public boolean isActive() {
    return status == AccountStatus.ACTIVE;
  }

  public UUID accountId() {
    return accountId;
  }

  public UUID personId() {
    return personId;
  }

  public LoginIdentifier loginIdentifier() {
    return loginIdentifier;
  }

  public AccountStatus status() {
    return status;
  }

  public int failedAttempts() {
    return failedAttempts;
  }

  public Instant lockedUntil() {
    return lockedUntil;
  }

  public boolean mustChangePassword() {
    return mustChangePassword;
  }

  public Instant temporaryPasswordExpiresAt() {
    return temporaryPasswordExpiresAt;
  }

  public int version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant deactivatedAt() {
    return deactivatedAt;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }
}
