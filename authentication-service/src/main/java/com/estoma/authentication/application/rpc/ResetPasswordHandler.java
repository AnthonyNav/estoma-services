package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
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
  private final CredentialRepository credentialRepository;
  private final SessionRepository sessionRepository;
  private final PasswordHasher passwordHasher;
  private final RecoveryCodeDeriver codeDeriver;
  private final TokenHasher tokenHasher;

  ResetPasswordHandler(
      PasswordRecoveryRepository recoveryRepository,
      AccountRepository accountRepository,
      CredentialRepository credentialRepository,
      SessionRepository sessionRepository,
      PasswordHasher passwordHasher,
      RecoveryCodeDeriver codeDeriver,
      TokenHasher tokenHasher) {
    this.recoveryRepository = recoveryRepository;
    this.accountRepository = accountRepository;
    this.credentialRepository = credentialRepository;
    this.sessionRepository = sessionRepository;
    this.passwordHasher = passwordHasher;
    this.codeDeriver = codeDeriver;
    this.tokenHasher = tokenHasher;
  }

  public RpcHandlerOutcome<ResetPasswordResponse> handle(ResetPasswordRequest request) {
    PasswordRecoveryRequest recovery =
        recoveryRepository.findById(request.recoveryId()).orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.RECOVERY_TOKEN_INVALID, "recovery request does not exist"));

    Instant now = Instant.now();
    if (recovery.isUsed()) {
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_TOKEN_ALREADY_USED, "recovery request was already used");
    }
    if (recovery.isExpired(now)) {
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_TOKEN_EXPIRED, "recovery request has expired");
    }
    if (recovery.failedAttempts() >= ValidateRecoveryCodeHandler.ATTEMPT_LIMIT) {
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_TOKEN_INVALID, "recovery request exceeded its validation attempt limit");
    }
    if (!matches(recovery, request.code())) {
      recoveryRepository.update(recovery.attemptFailed());
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_TOKEN_INVALID, "recovery code is incorrect");
    }
    if (request.newPassword() == null || request.newPassword().length() < 15) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_PASSWORD, "password must be at least 15 characters");
    }

    int consumed = recoveryRepository.markUsedIfValid(recovery.recoveryId(), now, now);
    if (consumed == 0) {
      throw new AuthenticationRejectedException(RejectionCode.RECOVERY_TOKEN_ALREADY_USED, "recovery request was consumed concurrently");
    }

    Account account =
        accountRepository
            .findById(recovery.accountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + recovery.accountId() + " no longer exists"));

    rejectPasswordReuse(account, request.newPassword());
    updateCredential(account, request.newPassword(), now);
    Account cleared = account.withPasswordChangeCompleted(now);
    accountRepository.update(cleared, account.version());
    sessionRepository.revokeAllActiveForAccount(account.accountId(), "password_reset", now);

    PasswordChangedPayload passwordEvent = new PasswordChangedPayload(account.accountId(), now, true);
    AllAccountSessionsRevokedPayload revokedEvent = new AllAccountSessionsRevokedPayload(account.accountId(), "password_reset", now);

    return RpcHandlerOutcome.of(
        new ResetPasswordResponse(true),
        account.accountId(),
        cleared.version(),
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

  private void rejectPasswordReuse(Account account, String newPassword) {
    credentialRepository
        .findByAccountId(account.accountId())
        .filter(credential -> passwordHasher.matches(newPassword, credential.hash()))
        .ifPresent(
            ignored -> {
              throw new AuthenticationRejectedException(
                  RejectionCode.INVALID_PASSWORD, "new password must differ from the current password");
            });
  }

  private boolean matches(PasswordRecoveryRequest recovery, String code) {
    return "v1-hmac".equals(recovery.tokenHash())
        ? codeDeriver.matches(recovery.recoveryId(), code)
        : recovery.tokenHash().equals(tokenHasher.sha256Hex(code));
  }
}
