package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.AllAccountSessionsRevokedPayload;
import com.estoma.authentication.contracts.event.PasswordChangedPayload;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.ResetPasswordResponse;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code ResetPassword} (commands.md §8.5): completes recovery via
 * a previously-validated code. Consumption is atomic ({@code
 * PasswordRecoveryRepository#markUsedIfValid}) so a race against a second
 * concurrent attempt cannot reset the password twice from one code.
 */
@Component
public class ResetPasswordHandler {

  private final PasswordRecoveryRepository recoveryRepository;
  private final AccountRepository accountRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final CredentialRepository credentialRepository;
  private final SessionRepository sessionRepository;
  private final PasswordHasher passwordHasher;
  private final PasswordPolicy passwordPolicy;
  private final RecoveryCodeDeriver codeDeriver;
  private final TokenHasher tokenHasher;

  ResetPasswordHandler(
      PasswordRecoveryRepository recoveryRepository,
      AccountRepository accountRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      CredentialRepository credentialRepository,
      SessionRepository sessionRepository,
      PasswordHasher passwordHasher,
      PasswordPolicy passwordPolicy,
      RecoveryCodeDeriver codeDeriver,
      TokenHasher tokenHasher) {
    this.recoveryRepository = recoveryRepository;
    this.accountRepository = accountRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.credentialRepository = credentialRepository;
    this.sessionRepository = sessionRepository;
    this.passwordHasher = passwordHasher;
    this.passwordPolicy = passwordPolicy;
    this.codeDeriver = codeDeriver;
    this.tokenHasher = tokenHasher;
  }

  public RpcHandlerOutcome<ResetPasswordResponse> handle(ResetPasswordRequest request) {
    LoginIdentifier identifier = LoginIdentifier.of(request.loginType(), request.loginIdentifier());
    Account account =
        accountRepository
            .findByLoginIdentifier(identifier.type(), identifier.normalized())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.RECOVERY_NOT_AVAILABLE, "recovery account does not exist"));
    if (!account.isActive()) {
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_NOT_AVAILABLE, "recovery account is not active");
    }
    if (!isPersonActive(account.personId())) {
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_NOT_AVAILABLE, "recovery person is not active");
    }
    PasswordRecoveryRequest recovery =
        recoveryRepository
            .findActiveByAccountIdForUpdate(account.accountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.RECOVERY_NOT_AVAILABLE, "no active recovery request exists"));

    Instant now = Instant.now();
    if (recovery.isExpired(now)) {
      recoveryRepository.expireIfActive(recovery.recoveryId(), now);
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_NOT_AVAILABLE, "recovery request has expired");
    }
    if (!matches(recovery, request.code())) {
      recoveryRepository.recordFailedAttempt(recovery.recoveryId(), now, ValidateRecoveryCodeHandler.ATTEMPT_LIMIT);
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_NOT_AVAILABLE, "recovery code is incorrect");
    }
    Credential currentCredential = credentialRepository.findByAccountId(account.accountId()).orElse(null);
    passwordPolicy.validate(request.newPassword(), currentCredential == null ? null : currentCredential.hash());

    int consumed = recoveryRepository.markUsedIfValid(recovery.recoveryId(), now, now);
    if (consumed == 0) {
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_NOT_AVAILABLE, "recovery request was consumed concurrently");
    }

    updateCredential(account, request.newPassword(), now);
    accountRepository.update(account.withAttemptsReset(now), account.version());
    recoveryRepository.invalidateActiveForAccount(account.accountId(), now);
    sessionRepository.revokeAllActiveForAccount(account.accountId(), "PASSWORD_RESET", now);

    PasswordChangedPayload passwordEvent = new PasswordChangedPayload(account.accountId(), "PASSWORD_RESET", now);
    AllAccountSessionsRevokedPayload revokedEvent = new AllAccountSessionsRevokedPayload(account.accountId(), "PASSWORD_RESET", now);

    return RpcHandlerOutcome.of(
        new ResetPasswordResponse(true),
        account.accountId(),
        account.version(),
        new EventToAppend(AuthenticationEvent.PASSWORD_CHANGED.wireName(), passwordEvent),
        new EventToAppend(AuthenticationEvent.ALL_ACCOUNT_SESSIONS_REVOKED.wireName(), revokedEvent));
  }

  private void updateCredential(Account account, String newPassword, Instant now) {
    var hash = passwordHasher.hash(newPassword);
    if (credentialRepository.existsByAccountId(account.accountId())) {
      Credential existing = credentialRepository.findByAccountId(account.accountId()).orElseThrow();
      credentialRepository.update(existing.changed(hash, now));
    } else {
      credentialRepository.insert(Credential.configure(account.accountId(), hash, now));
    }
  }

  private boolean matches(PasswordRecoveryRequest recovery, String code) {
    return "v1-hmac".equals(recovery.tokenHash())
        ? codeDeriver.matches(recovery.recoveryId(), code)
        : recovery.tokenHash().equals(tokenHasher.sha256Hex(code));
  }

  private boolean isPersonActive(java.util.UUID personId) {
    var status = personStatusProjectionRepository.findByPersonId(personId);
    return status.isPresent() && "ACTIVE".equals(status.get().status());
  }
}
