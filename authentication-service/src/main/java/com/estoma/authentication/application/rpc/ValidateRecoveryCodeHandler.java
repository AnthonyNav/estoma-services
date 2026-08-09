package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code ValidateRecoveryCode} (commands.md §8.4). Unlike the other
 * RPC operations, this one never rejects: the result is always a
 * successful response carrying {@code VALID}/{@code INVALID}/{@code
 * EXPIRED}, revealing no additional detail about the account.
 */
@Component
public class ValidateRecoveryCodeHandler {

  static final int ATTEMPT_LIMIT = 5;

  private final PasswordRecoveryRepository repository;
  private final RecoveryCodeDeriver codeDeriver;
  private final TokenHasher tokenHasher;

  ValidateRecoveryCodeHandler(PasswordRecoveryRepository repository, RecoveryCodeDeriver codeDeriver, TokenHasher tokenHasher) {
    this.repository = repository;
    this.codeDeriver = codeDeriver;
    this.tokenHasher = tokenHasher;
  }

  public RpcHandlerOutcome<ValidateRecoveryCodeResponse> handle(ValidateRecoveryCodeRequest request) {
    var found = repository.findById(request.recoveryId());
    if (found.isEmpty()) {
      return result("INVALID");
    }
    var recovery = found.get();
    Instant now = Instant.now();

    if (recovery.isUsed()) {
      return result("INVALID");
    }
    if (recovery.isExpired(now)) {
      return result("EXPIRED");
    }
    if (recovery.failedAttempts() >= ATTEMPT_LIMIT) {
      return result("INVALID");
    }
    if (!matches(recovery, request.code())) {
      repository.update(recovery.attemptFailed());
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
}
