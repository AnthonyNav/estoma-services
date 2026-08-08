package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.TemporaryPasswordRegeneratedPayload;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordResponse;
import com.estoma.authentication.domain.account.Account;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code RegenerateTemporaryPassword} (commands.md §7.2):
 * invalidates a previously-set temporary password and issues a new one.
 * Unlike {@code SetTemporaryPassword}, no session revocation is repeated
 * here -- sessions were already revoked the first time a temporary
 * password was set, and there is nothing new to revoke.
 */
@Component
public class RegenerateTemporaryPasswordHandler {

  private static final String OPERATION_TYPE = "REGENERATE_TEMPORARY_PASSWORD";

  private final TemporaryPasswordSupport support;
  private final TokenHasher tokenHasher;

  RegenerateTemporaryPasswordHandler(TemporaryPasswordSupport support, TokenHasher tokenHasher) {
    this.support = support;
    this.tokenHasher = tokenHasher;
  }

  public RpcHandlerOutcome<RegenerateTemporaryPasswordResponse> handle(RegenerateTemporaryPasswordRequest request) {
    Account account = support.requireAccount(request.accountId());

    String fingerprint = fingerprint(request);
    support.claimOrThrow(OPERATION_TYPE, request.idempotencyKey(), request.accountId(), request.requestedByAccountId(), fingerprint);

    Instant now = Instant.now();
    String temporaryPassword = support.issueNewSecretAndUpdateCredential(account);
    Account updated = support.markMustChangePassword(account, request.expiresAt(), now);

    support.markCompleted(OPERATION_TYPE, request.idempotencyKey(), now);

    TemporaryPasswordRegeneratedPayload event = new TemporaryPasswordRegeneratedPayload(updated.accountId(), true, updated.temporaryPasswordExpiresAt(), now);

    return RpcHandlerOutcome.of(
        new RegenerateTemporaryPasswordResponse(temporaryPassword, updated.temporaryPasswordExpiresAt()),
        updated.accountId(),
        updated.version(),
        new EventToAppend(AuthenticationEvent.TEMPORARY_PASSWORD_REGENERATED.wireName(), event));
  }

  private String fingerprint(RegenerateTemporaryPasswordRequest request) {
    return tokenHasher.sha256Hex(OPERATION_TYPE + "|" + request.accountId() + "|" + request.requestedByAccountId() + "|" + request.expiresAt());
  }
}
