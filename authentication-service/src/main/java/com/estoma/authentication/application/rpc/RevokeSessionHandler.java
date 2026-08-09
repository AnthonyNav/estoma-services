package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.SessionRevokedPayload;
import com.estoma.authentication.contracts.rpc.RevokeSessionRequest;
import com.estoma.authentication.contracts.rpc.RevokeSessionResponse;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.session.Session;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code RevokeSession} (commands.md §8.3), on behalf of the
 * session's own actor. Repeated revocation is idempotent: revoking an
 * already-revoked session is a no-op success, not a rejection.
 */
@Component
public class RevokeSessionHandler {

  private final SessionRepository sessionRepository;

  RevokeSessionHandler(SessionRepository sessionRepository) {
    this.sessionRepository = sessionRepository;
  }

  public RpcHandlerOutcome<RevokeSessionResponse> handle(RpcRequestContext context, RevokeSessionRequest request) {
    Session session =
        sessionRepository.findById(request.sessionId()).orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.SESSION_NOT_FOUND, "session " + request.sessionId() + " does not exist"));

    if (!session.accountId().equals(context.targetAccountId()) || !session.accountId().equals(context.actor().accountId())) {
      // Do not reveal that a session exists under a different account.
      throw new AuthenticationRejectedException(RejectionCode.SESSION_NOT_FOUND, "session " + request.sessionId() + " does not exist");
    }

    if (session.revokedAt() != null) {
      return RpcHandlerOutcome.withoutEvents(new RevokeSessionResponse(true));
    }

    Instant now = Instant.now();
    Session revoked = session.revoked("USER_REQUESTED", now);
    sessionRepository.update(revoked);

    SessionRevokedPayload event = new SessionRevokedPayload(revoked.sessionId(), revoked.accountId(), "USER_REQUESTED", now);
    return RpcHandlerOutcome.of(new RevokeSessionResponse(true), revoked.sessionId(), null, new EventToAppend(AuthenticationEvent.SESSION_REVOKED.wireName(), event));
  }

  @Deprecated
  public RpcHandlerOutcome<RevokeSessionResponse> handle(RevokeSessionRequest request) {
    Session session = sessionRepository.findById(request.sessionId()).orElseThrow();
    return handle(new RpcRequestContext(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "bff", new com.estoma.authentication.contracts.Actor("ACCOUNT", session.accountId(), null, "SELF", null), session.accountId(), null), request);
  }
}
