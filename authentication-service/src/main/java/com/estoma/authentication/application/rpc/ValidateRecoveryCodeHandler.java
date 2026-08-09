package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse;
import java.time.Instant;
import com.estoma.authentication.domain.account.LoginIdentifier;
import org.springframework.stereotype.Component;

/**
 * Handles {@code ValidateRecoveryCode} (commands.md §8.4). Unlike the other
 * RPC operations, this one never rejects: the result is always a
 * successful response carrying only {@code VALID}/{@code INVALID}, revealing
 * no additional detail about the account or recovery request.
 */
@Component
public class ValidateRecoveryCodeHandler {

  static final int ATTEMPT_LIMIT = 5;

  private final PasswordRecoveryRepository repository;
  private final AccountRepository accountRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final RecoveryCodeDeriver codeDeriver;
  private final TokenHasher tokenHasher;

  ValidateRecoveryCodeHandler(
      PasswordRecoveryRepository repository,
      AccountRepository accountRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      RecoveryCodeDeriver codeDeriver,
      TokenHasher tokenHasher) {
    this.repository = repository;
    this.accountRepository = accountRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.codeDeriver = codeDeriver;
    this.tokenHasher = tokenHasher;
  }

  public RpcHandlerOutcome<ValidateRecoveryCodeResponse> handle(ValidateRecoveryCodeRequest request) {
    var account = accountRepository.findByLoginIdentifier(
        request.loginType(), LoginIdentifier.of(request.loginType(), request.loginIdentifier()).normalized());
    if (account.isEmpty() || !account.get().isActive() || !isPersonActive(account.get().personId())) {
      return result("INVALID");
    }
    var found = repository.findActiveByAccountIdForUpdate(account.get().accountId());
    if (found.isEmpty()) {
      return result("INVALID");
    }
    var recovery = found.get();
    Instant now = Instant.now();

    if (recovery.isExpired(now)) {
      repository.expireIfActive(recovery.recoveryId(), now);
      return result("INVALID");
    }
    if (!matches(recovery, request.code())) {
      repository.recordFailedAttempt(recovery.recoveryId(), now, ATTEMPT_LIMIT);
      return result("INVALID");
    }
    return result("VALID");
  }

  private static RpcHandlerOutcome<ValidateRecoveryCodeResponse> result(String value) {
    return RpcHandlerOutcome.withoutEvents(new ValidateRecoveryCodeResponse(value));
  }

  private boolean matches(com.estoma.authentication.domain.recovery.PasswordRecoveryRequest recovery, String code) {
    return "v1-hmac".equals(recovery.tokenHash())
        ? codeDeriver.matches(recovery.recoveryId(), code)
        : recovery.tokenHash().equals(tokenHasher.sha256Hex(code));
  }

  private boolean isPersonActive(java.util.UUID personId) {
    var status = personStatusProjectionRepository.findByPersonId(personId);
    return status.isPresent() && "ACTIVE".equals(status.get().status());
  }
}
