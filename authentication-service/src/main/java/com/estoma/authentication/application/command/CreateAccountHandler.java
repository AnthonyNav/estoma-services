package com.estoma.authentication.application.command;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.command.CreateAccountCommand;
import com.estoma.authentication.contracts.event.AccountCreatedPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code CreateAccount} (commands.md §4.1). Never transports a
 * password or any secret; the credential is configured exclusively through
 * the ephemeral security RPC flow.
 */
@Component
class CreateAccountHandler implements CommandHandler {

  private final AccountRepository accountRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final EnvelopeJsonCodec codec;

  CreateAccountHandler(
      AccountRepository accountRepository, PersonStatusProjectionRepository personStatusProjectionRepository, EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return AuthenticationCommand.CREATE_ACCOUNT.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    CreateAccountCommand command = codec.convertPayload(envelope, CreateAccountCommand.class);

    var personStatus = personStatusProjectionRepository.findByPersonId(command.personId());
    if (personStatus.isEmpty()) {
      throw new AuthenticationRejectedException(RejectionCode.PERSON_NOT_FOUND, "person " + command.personId() + " is not known to this service");
    }
    if (!"ACTIVE".equals(personStatus.get().status())) {
      throw new AuthenticationRejectedException(RejectionCode.PERSON_INACTIVE, "person " + command.personId() + " is not active");
    }

    Instant now = Instant.now();
    LoginIdentifier loginIdentifier = LoginIdentifier.of(command.loginType(), command.loginIdentifier());
    Account account = Account.createNew(command.accountId(), command.personId(), loginIdentifier, now);

    // May itself throw AuthenticationRejectedException on a lost race against a concurrent writer.
    accountRepository.insert(account);

    AccountCreatedPayload eventPayload =
        new AccountCreatedPayload(account.accountId(), account.personId(), account.loginIdentifier().type(), account.status().name(), account.version(), account.createdAt());

    return CommandHandlerResult.of(
        account.accountId(), account.version(), new EventToAppend(AuthenticationEvent.ACCOUNT_CREATED.wireName(), eventPayload));
  }
}
