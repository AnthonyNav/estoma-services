package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.AllAccountSessionsRevokedPayload;
import com.estoma.authentication.contracts.event.TemporaryPasswordSetPayload;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordResponse;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.domain.account.Account;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code SetTemporaryPassword} (commands.md §7.1): generates a
 * temporary password, delivers it once via Direct Reply-To (once the
 * messaging shell exists -- this tramo returns it directly), and revokes
 * active sessions immediately.
 */
@Component
public class SetTemporaryPasswordHandler {

  private static final String OPERATION_TYPE = "SET_TEMPORARY_PASSWORD";

  private final TemporaryPasswordSupport support;
  private final SessionRepository sessionRepository;
  private final TokenHasher tokenHasher;

  SetTemporaryPasswordHandler(TemporaryPasswordSupport support, SessionRepository sessionRepository, TokenHasher tokenHasher) {
    this.support = support;
    this.sessionRepository = sessionRepository;
    this.tokenHasher = tokenHasher;
  }

  public RpcHandlerOutcome<SetTemporaryPasswordResponse> handle(RpcRequestContext context, SetTemporaryPasswordRequest request) {
    requireContextConsistency(context, request.accountId(), request.idempotencyKey());
    requireRequester(context, request.requestedByAccountId());
    Account account = support.requireAccount(context.targetAccountId());

    String fingerprint = fingerprint(request);
    support.claimOrThrow(OPERATION_TYPE, context.idempotencyKey(), context.targetAccountId(), context.actor().accountId(), fingerprint);

    Instant now = Instant.now();
    TemporaryPasswordSupport.IssuedTemporaryPassword issued = support.issueNewSecretAndUpdateCredential(account, now);
    sessionRepository.revokeAllActiveForAccount(account.accountId(), "TEMPORARY_PASSWORD_SET", now);

    support.markCompleted(OPERATION_TYPE, context.idempotencyKey(), now);

    TemporaryPasswordSetPayload setEvent = new TemporaryPasswordSetPayload(account.accountId(), true, issued.credential().temporaryPasswordExpiresAt(), now);
    AllAccountSessionsRevokedPayload revokedEvent = new AllAccountSessionsRevokedPayload(account.accountId(), "TEMPORARY_PASSWORD_SET", now);

    return RpcHandlerOutcome.of(
        new SetTemporaryPasswordResponse(issued.password(), issued.credential().temporaryPasswordExpiresAt()),
        account.accountId(),
        account.version(),
        new EventToAppend(AuthenticationEvent.TEMPORARY_PASSWORD_SET.wireName(), setEvent),
        new EventToAppend(AuthenticationEvent.ALL_ACCOUNT_SESSIONS_REVOKED.wireName(), revokedEvent));
  }

  @Deprecated
  public RpcHandlerOutcome<SetTemporaryPasswordResponse> handle(SetTemporaryPasswordRequest request) {
    return handle(new RpcRequestContext(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "bff", new com.estoma.authentication.contracts.Actor("ACCOUNT", request.requestedByAccountId(), null, "GLOBAL", null), request.accountId(), request.idempotencyKey()), request);
  }

  private String fingerprint(SetTemporaryPasswordRequest request) {
    return tokenHasher.sha256Hex(OPERATION_TYPE + "|" + request.accountId() + "|" + request.requestedByAccountId() + "|" + request.idempotencyKey());
  }

  private static void requireContextConsistency(RpcRequestContext context, java.util.UUID accountId, String idempotencyKey) {
    if (!java.util.Objects.equals(context.targetAccountId(), accountId) || !java.util.Objects.equals(context.idempotencyKey(), idempotencyKey)) {
      throw new com.estoma.authentication.domain.AuthenticationRejectedException(
          com.estoma.authentication.domain.RejectionCode.ACTOR_NOT_AUTHORIZED, "RPC context does not match request");
    }
  }

  private static void requireRequester(RpcRequestContext context, java.util.UUID requestedByAccountId) {
    if (!java.util.Objects.equals(context.actor().accountId(), requestedByAccountId)) {
      throw new com.estoma.authentication.domain.AuthenticationRejectedException(
          com.estoma.authentication.domain.RejectionCode.ACTOR_NOT_AUTHORIZED, "requester does not match actor");
    }
  }
}
