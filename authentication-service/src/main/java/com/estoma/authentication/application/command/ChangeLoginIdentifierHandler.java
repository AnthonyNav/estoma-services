package com.estoma.authentication.application.command;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.command.ChangeLoginIdentifierCommand;
import com.estoma.authentication.contracts.event.AccountPresentationUpdatedPayload;
import com.estoma.authentication.contracts.event.LoginIdentifierChangedPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Handles {@code ChangeLoginIdentifier} (commands.md §4.4). The identifier's type never changes, only its value. */
@Component
class ChangeLoginIdentifierHandler implements CommandHandler {

  private final AccountRepository accountRepository;
  private final EnvelopeJsonCodec codec;

  ChangeLoginIdentifierHandler(AccountRepository accountRepository, EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    ChangeLoginIdentifierCommand command = codec.convertPayload(envelope, ChangeLoginIdentifierCommand.class);

    Account account =
        accountRepository
            .findById(command.accountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + command.accountId() + " does not exist"));

    Instant now = Instant.now();
    if (account.loginIdentifier().type() == com.estoma.authentication.contracts.LoginType.MATRICULA) {
      throw new AuthenticationRejectedException(RejectionCode.LOGIN_TYPE_NOT_SUPPORTED, "MATRICULA login identifiers cannot be changed");
    }
    LoginIdentifier newIdentifier = LoginIdentifier.of(account.loginIdentifier().type(), command.newLoginIdentifier());
    Account updated = account.withLoginIdentifierChanged(newIdentifier, now);

    // May throw LOGIN_IDENTIFIER_ALREADY_USED on a lost race against a concurrent writer.
    int rowsAffected = accountRepository.update(updated, command.expectedVersion());
    if (rowsAffected == 0) {
      AccountUpdateGuard.rejectForFailedUpdate(accountRepository, command.accountId(), command.expectedVersion());
    }

    LoginIdentifierChangedPayload eventPayload = new LoginIdentifierChangedPayload(
        updated.accountId(), updated.loginIdentifier().type(), "ADMINISTRATIVE", now);
    AccountPresentationUpdatedPayload presentation = presentation(updated);
    return CommandHandlerResult.of(
        updated.accountId(), updated.version(),
        new EventToAppend(AuthenticationEvent.LOGIN_IDENTIFIER_CHANGED.wireName(), eventPayload),
        new EventToAppend(AuthenticationEvent.ACCOUNT_PRESENTATION_UPDATED.wireName(), presentation));
  }

  private static AccountPresentationUpdatedPayload presentation(Account account) {
    return new AccountPresentationUpdatedPayload(
        account.accountId(), account.personId(), account.loginIdentifier().type(),
        account.loginIdentifier().value(), account.status().name(), account.updatedAt());
  }
}
