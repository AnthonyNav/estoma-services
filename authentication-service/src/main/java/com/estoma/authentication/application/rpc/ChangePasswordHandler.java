package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.port.SecureRandomGenerator;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.application.port.TokenIssuer;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.OtherAccountSessionsRevokedPayload;
import com.estoma.authentication.contracts.event.PasswordChangedPayload;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.ChangePasswordResponse;
import com.estoma.authentication.contracts.rpc.AuthState;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.session.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@code ChangePassword} (commands.md §8.6), on behalf of the
 * authenticated actor's own account. Unlike {@code AuthenticateAccount},
 * this actor already proved who they are via a valid session, so account
 * and person inactivity are reported with their own specific codes rather
 * than folded into a generic rejection.
 */
@Component
public class ChangePasswordHandler {

  private static final Duration IDLE_SESSION_LIFETIME = Duration.ofDays(14);
  private static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofDays(30);

  private final AccountRepository accountRepository;
  private final CredentialRepository credentialRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final SessionRepository sessionRepository;
  private final PasswordHasher passwordHasher;
  private final PasswordPolicy passwordPolicy;
  private final TokenIssuer tokenIssuer;
  private final TokenHasher tokenHasher;
  private final SecureRandomGenerator randomGenerator;

  ChangePasswordHandler(
      AccountRepository accountRepository,
      CredentialRepository credentialRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      SessionRepository sessionRepository,
      PasswordHasher passwordHasher,
      PasswordPolicy passwordPolicy,
      TokenIssuer tokenIssuer,
      TokenHasher tokenHasher,
      SecureRandomGenerator randomGenerator) {
    this.accountRepository = accountRepository;
    this.credentialRepository = credentialRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.sessionRepository = sessionRepository;
    this.passwordHasher = passwordHasher;
    this.passwordPolicy = passwordPolicy;
    this.tokenIssuer = tokenIssuer;
    this.tokenHasher = tokenHasher;
    this.randomGenerator = randomGenerator;
  }

  public RpcHandlerOutcome<ChangePasswordResponse> handle(RpcRequestContext context, ChangePasswordRequest request) {
    Account account =
        accountRepository
            .findById(context.targetAccountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + context.targetAccountId() + " does not exist"));
    if (!account.isActive()) {
      throw new AuthenticationRejectedException(RejectionCode.ACCOUNT_INACTIVE, "account " + account.accountId() + " is not active");
    }
    if (!isPersonActive(account.personId())) {
      throw new AuthenticationRejectedException(RejectionCode.PERSON_INACTIVE, "person " + account.personId() + " is not active");
    }

    Instant now = Instant.now();
    Session currentSession =
        sessionRepository
            .findByIdForUpdate(request.sessionId())
            .filter(session -> session.accountId().equals(account.accountId()))
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.SESSION_NOT_FOUND, "session " + request.sessionId() + " does not exist"));
    if (!currentSession.isValid(now)) {
      throw new AuthenticationRejectedException(RejectionCode.SESSION_EXPIRED, "session " + currentSession.sessionId() + " has expired");
    }
    Credential credential =
        credentialRepository.findByAccountId(account.accountId()).orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.CREDENTIAL_NOT_CONFIGURED, "no credential configured"));
    passwordPolicy.validate(request.newPassword(), credential.hash());
    if (currentSession.authState() == Session.AuthState.NORMAL
        && !passwordHasher.matches(request.currentPassword(), credential.hash())) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_CREDENTIALS, "current password is incorrect");
    }

    String refreshToken = randomGenerator.randomUrlSafeToken(32);
    Instant absoluteExpiresAt = currentSession.authState() == Session.AuthState.PASSWORD_CHANGE_REQUIRED
        ? now.plus(ABSOLUTE_SESSION_LIFETIME)
        : currentSession.absoluteExpiresAt();
    Instant idleExpiresAt = minInstant(now.plus(IDLE_SESSION_LIFETIME), absoluteExpiresAt);
    Session renewedSession = currentSession.passwordChanged(tokenHasher.sha256Hex(refreshToken), now, idleExpiresAt, absoluteExpiresAt);
    credentialRepository.update(credential.changed(passwordHasher.hash(request.newPassword()), now));
    sessionRepository.update(renewedSession);
    int revokedOtherSessions =
        sessionRepository.revokeOtherActiveForAccount(account.accountId(), currentSession.sessionId(), "PASSWORD_CHANGED", now);
    TokenIssuer.IssuedAccessToken accessToken =
        tokenIssuer.issueAccessToken(account.accountId(), account.personId(), renewedSession.sessionId(), Session.AuthState.NORMAL.name());
    ChangePasswordResponse response = new ChangePasswordResponse(renewedSession.sessionId(), accessToken.token(), refreshToken, AuthState.NORMAL);

    PasswordChangedPayload event = new PasswordChangedPayload(account.accountId(), "CHANGE_PASSWORD", now);
    if (revokedOtherSessions == 0) {
      return RpcHandlerOutcome.of(
          response, account.accountId(), account.version(), new EventToAppend(AuthenticationEvent.PASSWORD_CHANGED.wireName(), event));
    }
    OtherAccountSessionsRevokedPayload sessionsRevoked =
        new OtherAccountSessionsRevokedPayload(account.accountId(), currentSession.sessionId(), "PASSWORD_CHANGED", now);
    return RpcHandlerOutcome.of(
        response,
        account.accountId(),
        account.version(),
        new EventToAppend(AuthenticationEvent.PASSWORD_CHANGED.wireName(), event),
        new EventToAppend(AuthenticationEvent.OTHER_ACCOUNT_SESSIONS_REVOKED.wireName(), sessionsRevoked));
  }

  private boolean isPersonActive(UUID personId) {
    var status = personStatusProjectionRepository.findByPersonId(personId);
    return status.isPresent() && "ACTIVE".equals(status.get().status());
  }

  private static Instant minInstant(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
