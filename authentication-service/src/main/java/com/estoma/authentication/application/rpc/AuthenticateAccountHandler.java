package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SecureRandomGenerator;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.application.port.TokenIssuer;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountResponse;
import com.estoma.authentication.contracts.rpc.AuthState;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.session.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@code AuthenticateAccount} (commands.md §8.1). A failure always
 * rejects with {@code INVALID_CREDENTIALS}, never revealing whether the
 * identifier or the password was wrong, EXCEPT {@code ACCOUNT_LOCKED},
 * which is deliberately its own catalog code (system_contract.md "Lockout
 * Policy (v1)") -- a locked account legitimately needs to tell the user to
 * wait, unlike account existence.
 *
 * <p>A failed attempt still increments {@code failed_attempts} (and may set
 * {@code locked_until}) before this method throws; {@code RpcDispatcher}
 * catches the rejection AFTER that state change already ran, so it commits
 * regardless of the overall RPC outcome.
 */
@Component
public class AuthenticateAccountHandler {

  private static final int FAILED_ATTEMPT_LIMIT = 5;
  private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
  private static final Duration IDLE_SESSION_LIFETIME = Duration.ofDays(14);
  private static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofDays(30);
  private static final Duration PASSWORD_CHANGE_SESSION_LIFETIME = Duration.ofMinutes(15);

  private final AccountRepository accountRepository;
  private final CredentialRepository credentialRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final SessionRepository sessionRepository;
  private final PasswordHasher passwordHasher;
  private final TokenIssuer tokenIssuer;
  private final TokenHasher tokenHasher;
  private final SecureRandomGenerator randomGenerator;

  AuthenticateAccountHandler(
      AccountRepository accountRepository,
      CredentialRepository credentialRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      SessionRepository sessionRepository,
      PasswordHasher passwordHasher,
      TokenIssuer tokenIssuer,
      TokenHasher tokenHasher,
      SecureRandomGenerator randomGenerator) {
    this.accountRepository = accountRepository;
    this.credentialRepository = credentialRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.sessionRepository = sessionRepository;
    this.passwordHasher = passwordHasher;
    this.tokenIssuer = tokenIssuer;
    this.tokenHasher = tokenHasher;
    this.randomGenerator = randomGenerator;
  }

  public RpcHandlerOutcome<AuthenticateAccountResponse> handle(AuthenticateAccountRequest request) {
    LoginIdentifier identifier = LoginIdentifier.of(request.loginType(), request.loginIdentifier());
    Optional<Account> accountOpt = accountRepository.findByLoginIdentifierForUpdate(identifier.type(), identifier.normalized());
    if (accountOpt.isEmpty()) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_CREDENTIALS, "identifier or password is incorrect");
    }
    Account account = accountOpt.get();

    Instant now = Instant.now();
    if (account.isLocked(now)) {
      throw new AuthenticationRejectedException(RejectionCode.ACCOUNT_LOCKED, "account " + account.accountId() + " is temporarily locked");
    }

    Optional<Credential> credential = account.isActive() ? credentialRepository.findByAccountId(account.accountId()) : Optional.empty();
    boolean personActive = isPersonActive(account.personId());
    boolean passwordMatches = credential.isPresent() && passwordHasher.matches(request.password(), credential.get().hash());
    boolean temporaryCredential = credential.isPresent() && credential.get().isValidTemporaryPassword(now);

    if (credential.isEmpty() || !personActive || !passwordMatches || (credential.get().mustChangePassword() && !temporaryCredential)) {
      recordFailedAttempt(account, now);
      throw new AuthenticationRejectedException(RejectionCode.INVALID_CREDENTIALS, "identifier or password is incorrect");
    }

    Account reset = account.withAttemptsReset(now);
    accountRepository.update(reset, account.version());

    Session session;
    String refreshToken;
    if (temporaryCredential) {
      if (credentialRepository.consumeTemporaryIfValid(account.accountId(), credential.get().credentialVersion(), now) == 0) {
        throw new AuthenticationRejectedException(RejectionCode.INVALID_CREDENTIALS, "identifier or password is incorrect");
      }
      refreshToken = null;
      session = Session.createPasswordChangeRequired(UUID.randomUUID(), account.accountId(), request.deviceName(), request.ipAddress(),
          request.userAgent(), now, now.plus(PASSWORD_CHANGE_SESSION_LIFETIME));
    } else {
      refreshToken = randomGenerator.randomUrlSafeToken(32);
      session = Session.createNormal(UUID.randomUUID(), account.accountId(), tokenHasher.sha256Hex(refreshToken), request.deviceName(),
          request.ipAddress(), request.userAgent(), now, now.plus(IDLE_SESSION_LIFETIME), now.plus(ABSOLUTE_SESSION_LIFETIME));
    }
    sessionRepository.insert(session);

    TokenIssuer.IssuedAccessToken accessToken = tokenIssuer.issueAccessToken(account.accountId(), account.personId(), session.sessionId(), session.authState().name());

    AuthenticateAccountResponse response =
        new AuthenticateAccountResponse(account.accountId(), session.sessionId(), accessToken.token(), refreshToken, AuthState.valueOf(session.authState().name()));
    return RpcHandlerOutcome.withoutEvents(response); // AuthenticationSucceeded is internal, not published in v1 (events.md §5)
  }

  private void recordFailedAttempt(Account account, Instant now) {
    int nextAttempts = account.failedAttempts() + 1;
    Instant lockUntil = nextAttempts >= FAILED_ATTEMPT_LIMIT ? now.plus(LOCK_DURATION) : account.lockedUntil();
    Account updated = account.withFailedAttemptRecorded(now, lockUntil);
    accountRepository.update(updated, account.version());
  }

  private boolean isPersonActive(UUID personId) {
    var status = personStatusProjectionRepository.findByPersonId(personId);
    return status.isPresent() && "ACTIVE".equals(status.get().status());
  }
}
