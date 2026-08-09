package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SecureRandomGenerator;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.application.port.TokenIssuer;
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionResponse;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.SessionRevokedPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.session.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@code RefreshSession} (commands.md §8.2): rotate-on-use,
 * invalidating the previous refresh token. Session lifetime never extends
 * past 30 days from {@code createdAt} (system_contract.md "Sessions").
 */
@Component
public class RefreshSessionHandler {

  private static final Duration REFRESH_EXTENSION = Duration.ofDays(14);
  private static final Duration REFRESH_GRACE = Duration.ofSeconds(30);

  private final SessionRepository sessionRepository;
  private final AccountRepository accountRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final TokenIssuer tokenIssuer;
  private final TokenHasher tokenHasher;
  private final SecureRandomGenerator randomGenerator;

  RefreshSessionHandler(
      SessionRepository sessionRepository,
      AccountRepository accountRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      TokenIssuer tokenIssuer,
      TokenHasher tokenHasher,
      SecureRandomGenerator randomGenerator) {
    this.sessionRepository = sessionRepository;
    this.accountRepository = accountRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.tokenIssuer = tokenIssuer;
    this.tokenHasher = tokenHasher;
    this.randomGenerator = randomGenerator;
  }

  public RpcHandlerOutcome<RefreshSessionResponse> handle(RefreshSessionRequest request) {
    Session session =
        sessionRepository
            .findByIdForUpdate(request.sessionId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.SESSION_NOT_FOUND, "session " + request.sessionId() + " does not exist"));

    if (session.revokedAt() != null) {
      throw new AuthenticationRejectedException(RejectionCode.SESSION_REVOKED, "session " + session.sessionId() + " was revoked");
    }
    Instant now = Instant.now();
    if (!session.isValid(now)) {
      throw new AuthenticationRejectedException(RejectionCode.SESSION_EXPIRED, "session " + session.sessionId() + " has expired");
    }
    Session.RefreshTokenMatch tokenMatch = session.classifyRefreshTokenHash(tokenHasher.sha256Hex(request.refreshToken()), now);
    if (tokenMatch == Session.RefreshTokenMatch.PREVIOUS_OUTSIDE_GRACE) {
      Session revoked = session.revoked("refresh_token_replay", now);
      sessionRepository.update(revoked);
      SessionRevokedPayload event = new SessionRevokedPayload(revoked.sessionId(), revoked.accountId(), "refresh_token_replay", now);
      return RpcHandlerOutcome.rejected(
          RejectionCode.REFRESH_TOKEN_REPLAY,
          revoked.sessionId(),
          null,
          new EventToAppend(AuthenticationEvent.SESSION_REVOKED.wireName(), event));
    }
    if (session.authState() != Session.AuthState.NORMAL
        || (tokenMatch != Session.RefreshTokenMatch.CURRENT
            && tokenMatch != Session.RefreshTokenMatch.PREVIOUS_WITHIN_GRACE)) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_REFRESH_TOKEN, "refresh token does not match the current session token");
    }

    Account account =
        accountRepository
            .findById(session.accountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.INVALID_REFRESH_TOKEN, "session's account no longer exists"));
    if (!account.isActive() || !isPersonActive(account.personId())) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_REFRESH_TOKEN, "account or person is no longer active");
    }

    String newRefreshToken = randomGenerator.randomUrlSafeToken(32);
    Instant cappedExpiresAt = minInstant(now.plus(REFRESH_EXTENSION), session.absoluteExpiresAt());
    Session rotated = session.rotated(tokenHasher.sha256Hex(newRefreshToken), now, cappedExpiresAt, now.plus(REFRESH_GRACE));
    sessionRepository.update(rotated);

    TokenIssuer.IssuedAccessToken accessToken = tokenIssuer.issueAccessToken(account.accountId(), account.personId(), session.sessionId(), session.authState().name());

    // SessionRenewed is internal, not published in v1 (events.md §5).
    return RpcHandlerOutcome.withoutEvents(new RefreshSessionResponse(accessToken.token(), newRefreshToken, cappedExpiresAt));
  }

  private boolean isPersonActive(UUID personId) {
    var status = personStatusProjectionRepository.findByPersonId(personId);
    return status.isPresent() && "ACTIVE".equals(status.get().status());
  }

  private static Instant minInstant(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
