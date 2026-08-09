package com.estoma.authentication.application.command;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.command.RevokeAllAccountSessionsCommand;
import com.estoma.authentication.contracts.event.AllAccountSessionsRevokedPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code RevokeAllAccountSessions} (commands.md §5.1): an
 * administrative revocation that does not itself change {@code
 * accounts.version} (unlike {@code DeactivateAccount}, which also revokes
 * sessions as a side effect of a real Account state transition).
 */
@Component
class RevokeAllAccountSessionsHandler implements CommandHandler {

  private final AccountRepository accountRepository;
  private final SessionRepository sessionRepository;
  private final EnvelopeJsonCodec codec;

  RevokeAllAccountSessionsHandler(AccountRepository accountRepository, SessionRepository sessionRepository, EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.sessionRepository = sessionRepository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return AuthenticationCommand.REVOKE_ALL_ACCOUNT_SESSIONS.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    RevokeAllAccountSessionsCommand command = codec.convertPayload(envelope, RevokeAllAccountSessionsCommand.class);

    Account account =
        accountRepository
            .findById(command.accountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + command.accountId() + " does not exist"));

    Instant now = Instant.now();
    String reasonCode = command.reason().toUpperCase(java.util.Locale.ROOT);
    sessionRepository.revokeAllActiveForAccount(command.accountId(), reasonCode, now);

    AllAccountSessionsRevokedPayload eventPayload = new AllAccountSessionsRevokedPayload(command.accountId(), reasonCode, now);
    return CommandHandlerResult.of(account.accountId(), account.version(), new EventToAppend(AuthenticationEvent.ALL_ACCOUNT_SESSIONS_REVOKED.wireName(), eventPayload));
  }
}
