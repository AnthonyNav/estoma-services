package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.PasswordChangedPayload;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.ChangePasswordResponse;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.credential.Credential;
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

  private final AccountRepository accountRepository;
  private final CredentialRepository credentialRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final SessionRepository sessionRepository;
  private final PasswordHasher passwordHasher;

  ChangePasswordHandler(
      AccountRepository accountRepository,
      CredentialRepository credentialRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      SessionRepository sessionRepository,
      PasswordHasher passwordHasher) {
    this.accountRepository = accountRepository;
    this.credentialRepository = credentialRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.sessionRepository = sessionRepository;
    this.passwordHasher = passwordHasher;
  }

  public RpcHandlerOutcome<ChangePasswordResponse> handle(ChangePasswordRequest request) {
    Account account =
        accountRepository
            .findById(request.accountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + request.accountId() + " does not exist"));
    if (!account.isActive()) {
      throw new AuthenticationRejectedException(RejectionCode.ACCOUNT_INACTIVE, "account " + account.accountId() + " is not active");
    }
    if (!isPersonActive(account.personId())) {
      throw new AuthenticationRejectedException(RejectionCode.PERSON_INACTIVE, "person " + account.personId() + " is not active");
    }

    Credential credential =
        credentialRepository.findByAccountId(account.accountId()).orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.CREDENTIAL_NOT_CONFIGURED, "no credential configured"));
    if (!passwordHasher.matches(request.currentPassword(), credential.hash())) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_CREDENTIALS, "current password is incorrect");
    }
    if (request.newPassword() == null || request.newPassword().length() < 10) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_PASSWORD, "password must be at least 10 characters");
    }
    if (passwordHasher.matches(request.newPassword(), credential.hash())) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_PASSWORD, "new password must differ from the current password");
    }

    Instant now = Instant.now();
    credentialRepository.update(credential.changed(passwordHasher.hash(request.newPassword()), now));
    Account cleared = account.withPasswordChangeCompleted(now);
    accountRepository.update(cleared, account.version());
    // v1 policy: revoke every session after a password change (system_contract.md "Sessions").
    sessionRepository.revokeAllActiveForAccount(account.accountId(), "password_changed", now);

    PasswordChangedPayload event = new PasswordChangedPayload(account.accountId(), now, true);
    return RpcHandlerOutcome.of(new ChangePasswordResponse(true), account.accountId(), cleared.version(), new EventToAppend(AuthenticationEvent.PASSWORD_CHANGED.wireName(), event));
  }

  private boolean isPersonActive(UUID personId) {
    var status = personStatusProjectionRepository.findByPersonId(personId);
    return status.isEmpty() || "ACTIVE".equals(status.get().status());
  }
}
