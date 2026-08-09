package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.TemporaryPasswordRegeneratedPayload;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordResponse;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
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

  public RpcHandlerOutcome<RegenerateTemporaryPasswordResponse> handle(RpcRequestContext context, RegenerateTemporaryPasswordRequest request) {
    if (!java.util.Objects.equals(context.targetAccountId(), request.accountId()) || !java.util.Objects.equals(context.idempotencyKey(), request.idempotencyKey())) {
      throw new com.estoma.authentication.domain.AuthenticationRejectedException(
          com.estoma.authentication.domain.RejectionCode.ACTOR_NOT_AUTHORIZED, "RPC context does not match request");
    }
    if (!java.util.Objects.equals(context.actor().accountId(), request.requestedByAccountId())) {
      throw new com.estoma.authentication.domain.AuthenticationRejectedException(
          com.estoma.authentication.domain.RejectionCode.ACTOR_NOT_AUTHORIZED, "requester does not match actor");
    }
    Account account = support.requireAccount(context.targetAccountId());

    String fingerprint = fingerprint(request);
    support.claimOrThrow(OPERATION_TYPE, context.idempotencyKey(), context.targetAccountId(), context.actor().accountId(), fingerprint);

    Instant now = Instant.now();
    TemporaryPasswordSupport.IssuedTemporaryPassword issued = support.issueNewSecretAndUpdateCredential(account, now);

    support.markCompleted(OPERATION_TYPE, context.idempotencyKey(), now);

    TemporaryPasswordRegeneratedPayload event = new TemporaryPasswordRegeneratedPayload(account.accountId(), true, issued.credential().temporaryPasswordExpiresAt(), now);

    return RpcHandlerOutcome.of(
        new RegenerateTemporaryPasswordResponse(issued.password(), issued.credential().temporaryPasswordExpiresAt()),
        account.accountId(),
        account.version(),
        new EventToAppend(AuthenticationEvent.TEMPORARY_PASSWORD_REGENERATED.wireName(), event));
  }

  private String fingerprint(RegenerateTemporaryPasswordRequest request) {
    return tokenHasher.sha256Hex(OPERATION_TYPE + "|" + request.accountId() + "|" + request.requestedByAccountId() + "|" + request.idempotencyKey());
  }
}
