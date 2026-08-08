package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.AllAccountSessionsRevokedPayload;
import com.estoma.authentication.contracts.event.TemporaryPasswordSetPayload;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordResponse;
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

  public RpcHandlerOutcome<SetTemporaryPasswordResponse> handle(SetTemporaryPasswordRequest request) {
    Account account = support.requireAccount(request.accountId());

    String fingerprint = fingerprint(request);
    support.claimOrThrow(OPERATION_TYPE, request.idempotencyKey(), request.accountId(), request.requestedByAccountId(), fingerprint);

    Instant now = Instant.now();
    String temporaryPassword = support.issueNewSecretAndUpdateCredential(account);
    Account updated = support.markMustChangePassword(account, request.expiresAt(), now);
    sessionRepository.revokeAllActiveForAccount(account.accountId(), "temporary_password_set", now);

    support.markCompleted(OPERATION_TYPE, request.idempotencyKey(), now);

    TemporaryPasswordSetPayload setEvent = new TemporaryPasswordSetPayload(updated.accountId(), true, updated.temporaryPasswordExpiresAt(), now);
    AllAccountSessionsRevokedPayload revokedEvent = new AllAccountSessionsRevokedPayload(updated.accountId(), "temporary_password_set", now);

    return RpcHandlerOutcome.of(
        new SetTemporaryPasswordResponse(temporaryPassword, updated.temporaryPasswordExpiresAt()),
        updated.accountId(),
        updated.version(),
        new EventToAppend(AuthenticationEvent.TEMPORARY_PASSWORD_SET.wireName(), setEvent),
        new EventToAppend(AuthenticationEvent.ALL_ACCOUNT_SESSIONS_REVOKED.wireName(), revokedEvent));
  }

  private String fingerprint(SetTemporaryPasswordRequest request) {
    return tokenHasher.sha256Hex(OPERATION_TYPE + "|" + request.accountId() + "|" + request.requestedByAccountId() + "|" + request.expiresAt());
  }
}
