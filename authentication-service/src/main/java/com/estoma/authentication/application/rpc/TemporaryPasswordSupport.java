package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.SecureRandomGenerator;
import com.estoma.authentication.application.port.SecurityRpcIdempotencyRepository;
import com.estoma.authentication.application.port.SecurityRpcIdempotencyRepository.ClaimResult;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.credential.PasswordHash;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared logic between {@code SetTemporaryPassword} and {@code
 * RegenerateTemporaryPassword} (commands.md §7): confirm the account
 * exists, claim the idempotency key, generate and store the secret, update
   * the credential's must-change-password state. Differs only in which event(s)
 * the caller appends and the {@code operationType} used for the
 * idempotency ledger -- left to each handler.
 *
 * <p>The account check runs BEFORE claiming the idempotency key, not after
 * (commands.md §7.1 does not order the two explicitly): {@code
 * security_rpc_idempotency_records.account_id} has a real foreign key to
 * {@code accounts} (it only ever protects operations against accounts that
 * exist locally, unlike the student-number sync projection), so claiming
 * first against a nonexistent account would fail on the constraint itself
 * rather than returning a clean {@code ACCOUNT_NOT_FOUND} rejection.
 * Nothing is lost by checking first: no secret is generated on this path
 * either way.
 */
@Component
class TemporaryPasswordSupport {

  private static final Duration DEFAULT_EXPIRATION = Duration.ofHours(24);

  private final SecurityRpcIdempotencyRepository idempotencyRepository;
  private final AccountRepository accountRepository;
  private final CredentialRepository credentialRepository;
  private final SecureRandomGenerator randomGenerator;
  private final PasswordHasher passwordHasher;

  TemporaryPasswordSupport(
      SecurityRpcIdempotencyRepository idempotencyRepository,
      AccountRepository accountRepository,
      CredentialRepository credentialRepository,
      SecureRandomGenerator randomGenerator,
      PasswordHasher passwordHasher) {
    this.idempotencyRepository = idempotencyRepository;
    this.accountRepository = accountRepository;
    this.credentialRepository = credentialRepository;
    this.randomGenerator = randomGenerator;
    this.passwordHasher = passwordHasher;
  }

  /** Claims {@code idempotencyKey}; throws the correct rejection if it cannot proceed. */
  void claimOrThrow(String operationType, String idempotencyKey, UUID accountId, UUID requestedByAccountId, String requestFingerprint) {
    ClaimResult claim =
        idempotencyRepository.tryClaim(operationType, idempotencyKey, accountId, requestedByAccountId, requestFingerprint, Instant.now().plus(DEFAULT_EXPIRATION));
    switch (claim) {
      case SecurityRpcIdempotencyRepository.Claimed ignored -> {}
      case SecurityRpcIdempotencyRepository.AlreadyCompleted ignored ->
          throw new AuthenticationRejectedException(RejectionCode.ALREADY_EXECUTED_SECRET_NOT_RECOVERABLE, "idempotencyKey already completed; the generated secret cannot be recovered");
      case SecurityRpcIdempotencyRepository.AlreadyFailed failed -> throw new AuthenticationRejectedException(RejectionCode.valueOf(failed.errorCode()), "idempotencyKey previously failed");
      case SecurityRpcIdempotencyRepository.FingerprintMismatch ignored ->
          throw new AuthenticationRejectedException(RejectionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST, "idempotencyKey reused with a different request");
    }
  }

  Account requireAccount(UUID accountId) {
    return accountRepository
        .findByIdForUpdate(accountId)
        .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + accountId + " does not exist"));
  }

  /** Generates a new temporary password, stores only its hash, and returns the plain-text secret (never persisted). */
  IssuedTemporaryPassword issueNewSecretAndUpdateCredential(Account account, Instant now) {
    Account resetAccount = account.withAttemptsReset(now);
    if (accountRepository.update(resetAccount, account.version()) != 1) {
      throw new AuthenticationRejectedException(RejectionCode.ACCOUNT_VERSION_CONFLICT, "account changed while issuing temporary password");
    }
    String temporaryPassword = randomGenerator.randomTemporaryPassword();
    PasswordHash hash = passwordHasher.hash(temporaryPassword);
    Instant expiresAt = now.plus(DEFAULT_EXPIRATION);

    if (credentialRepository.existsByAccountId(account.accountId())) {
      Credential existing = credentialRepository.findByAccountId(account.accountId()).orElseThrow();
      Credential updated = existing.temporaryPassword(hash, expiresAt, now);
      credentialRepository.update(updated);
      return new IssuedTemporaryPassword(temporaryPassword, updated);
    } else {
      Credential created = Credential.configureTemporary(account.accountId(), hash, expiresAt, now);
      credentialRepository.insert(created);
      return new IssuedTemporaryPassword(temporaryPassword, created);
    }
  }

  void markCompleted(String operationType, String idempotencyKey, Instant now) {
    idempotencyRepository.markCompleted(operationType, idempotencyKey, now);
  }

  record IssuedTemporaryPassword(String password, Credential credential) {}
}
