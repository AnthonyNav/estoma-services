package com.estoma.authentication.application.command;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.AccountStatusHistoryRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.command.DeactivateAccountCommand;
import com.estoma.authentication.contracts.event.AccountDeactivatedPayload;
import com.estoma.authentication.contracts.event.AllAccountSessionsRevokedPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.AccountDeactivationReason;
import com.estoma.authentication.domain.account.AccountStatusChange;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@code DeactivateAccount} (commands.md §4.3): deactivates the
 * account and revokes its sessions in one transaction, producing both
 * {@code AccountDeactivated} and {@code AllAccountSessionsRevoked}.
 */
@Component
class DeactivateAccountHandler implements CommandHandler {

  private final AccountRepository accountRepository;
  private final SessionRepository sessionRepository;
  private final AccountStatusHistoryRepository statusHistoryRepository;
  private final EnvelopeJsonCodec codec;

  DeactivateAccountHandler(
      AccountRepository accountRepository,
      SessionRepository sessionRepository,
      AccountStatusHistoryRepository statusHistoryRepository,
      EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.sessionRepository = sessionRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    DeactivateAccountCommand command = codec.convertPayload(envelope, DeactivateAccountCommand.class);

    Account account =
        accountRepository
            .findById(command.accountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + command.accountId() + " does not exist"));

    AccountDeactivationReason reason = parseReason(command.reasonCode());
    Instant now = Instant.now();
    Account deactivated = account.deactivated(now, reason);

    int rowsAffected = accountRepository.update(deactivated, command.expectedVersion());
    if (rowsAffected == 0) {
      AccountUpdateGuard.rejectForFailedUpdate(accountRepository, command.accountId(), command.expectedVersion());
    }
    statusHistoryRepository.insert(
        new AccountStatusChange(
            UUID.randomUUID(), deactivated.accountId(), account.status(), deactivated.status(), reason.name(), command.reasonDetail(), envelope.actor(),
            envelope.correlationId(), now, deactivated.version()));

    sessionRepository.revokeAllActiveForAccount(command.accountId(), "account_deactivated", now);

    AccountDeactivatedPayload accountEvent = new AccountDeactivatedPayload(deactivated.accountId(), deactivated.personId(), deactivated.status().name(), deactivated.version(), now);
    AllAccountSessionsRevokedPayload sessionsEvent = new AllAccountSessionsRevokedPayload(deactivated.accountId(), "account_deactivated", now);

    return CommandHandlerResult.of(
        deactivated.accountId(),
        deactivated.version(),
        new EventToAppend(AuthenticationEvent.ACCOUNT_DEACTIVATED.wireName(), accountEvent),
        new EventToAppend(AuthenticationEvent.ALL_ACCOUNT_SESSIONS_REVOKED.wireName(), sessionsEvent));
  }

  private static AccountDeactivationReason parseReason(String reasonCode) {
    if (reasonCode == null || reasonCode.isBlank()) {
      throw new IllegalArgumentException("reasonCode is required");
    }
    try {
      return AccountDeactivationReason.valueOf(reasonCode);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("reasonCode is not a recognized value: " + reasonCode, e);
    }
  }
}
