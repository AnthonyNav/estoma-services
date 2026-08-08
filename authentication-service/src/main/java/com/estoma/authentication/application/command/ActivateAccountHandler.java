package com.estoma.authentication.application.command;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.command.ActivateAccountCommand;
import com.estoma.authentication.contracts.event.AccountActivatedPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code ActivateAccount} (commands.md §4.2): the explicit step
 * after {@code CreateAccount} that requires a credential to already be
 * configured through the ephemeral security RPC flow.
 */
@Component
class ActivateAccountHandler implements CommandHandler {

  private final AccountRepository accountRepository;
  private final CredentialRepository credentialRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final EnvelopeJsonCodec codec;

  ActivateAccountHandler(
      AccountRepository accountRepository,
      CredentialRepository credentialRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.credentialRepository = credentialRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return AuthenticationCommand.ACTIVATE_ACCOUNT.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    ActivateAccountCommand command = codec.convertPayload(envelope, ActivateAccountCommand.class);

    Account account =
        accountRepository
            .findById(command.accountId())
            .orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + command.accountId() + " does not exist"));

    if (!credentialRepository.existsByAccountId(command.accountId())) {
      throw new AuthenticationRejectedException(RejectionCode.CREDENTIAL_NOT_CONFIGURED, "account " + command.accountId() + " has no configured credential");
    }

    var personStatus = personStatusProjectionRepository.findByPersonId(account.personId());
    if (personStatus.isPresent() && !"ACTIVE".equals(personStatus.get().status())) {
      throw new AuthenticationRejectedException(RejectionCode.PERSON_INACTIVE, "person " + account.personId() + " is not active");
    }

    Instant now = Instant.now();
    Account activated = account.activated(now); // throws ACCOUNT_ALREADY_ACTIVE

    int rowsAffected = accountRepository.update(activated, command.expectedVersion());
    if (rowsAffected == 0) {
      AccountUpdateGuard.rejectForFailedUpdate(accountRepository, command.accountId(), command.expectedVersion());
    }

    AccountActivatedPayload eventPayload = new AccountActivatedPayload(activated.accountId(), activated.personId(), activated.status().name(), activated.version(), now);
    return CommandHandlerResult.of(activated.accountId(), activated.version(), new EventToAppend(AuthenticationEvent.ACCOUNT_ACTIVATED.wireName(), eventPayload));
  }
}
