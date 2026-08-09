package com.estoma.authentication.application.command;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.command.RequestPasswordRecoveryCommand;
import com.estoma.authentication.contracts.event.PasswordRecoveryRequestedPayload;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@code RequestPasswordRecovery} (commands.md §6.1). The result is
 * always generic -- {@code aggregateId}/{@code aggregateVersion} are always
 * null here, whether or not an account was found -- so this operation never
 * reveals whether a login identifier exists. When an account genuinely
 * exists and is active, a recovery request is created and {@code
 * PasswordRecoveryRequested} is appended (without the code itself); when it
 * is not, nothing else happens.
 */
@Component
class RequestPasswordRecoveryHandler implements CommandHandler {

  private static final java.time.Duration RECOVERY_EXPIRATION = java.time.Duration.ofMinutes(10);
  private static final java.time.Duration RECOVERY_COOLDOWN = java.time.Duration.ofMinutes(5);

  private final AccountRepository accountRepository;
  private final PasswordRecoveryRepository passwordRecoveryRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final EnvelopeJsonCodec codec;

  RequestPasswordRecoveryHandler(
      AccountRepository accountRepository,
      PasswordRecoveryRepository passwordRecoveryRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.passwordRecoveryRepository = passwordRecoveryRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    RequestPasswordRecoveryCommand command = codec.convertPayload(envelope, RequestPasswordRecoveryCommand.class);

    LoginIdentifier identifier = LoginIdentifier.of(command.loginType(), command.loginIdentifier());
    // The account row serializes the initial-request case, when no recovery row exists to lock.
    Optional<Account> account = accountRepository.findByLoginIdentifierForUpdate(identifier.type(), identifier.normalized());

    if (account.isEmpty() || !account.get().isActive() || !isPersonActive(account.get().personId())) {
      return new CommandHandlerResult(null, null, List.of());
    }

    Instant now = Instant.now();
    if (account.get().lastRecoveryRequestedAt() != null
        && account.get().lastRecoveryRequestedAt().plus(RECOVERY_COOLDOWN).isAfter(now)) {
      return new CommandHandlerResult(null, null, List.of());
    }
    passwordRecoveryRepository.invalidateActiveForAccount(account.get().accountId(), now);
    PasswordRecoveryRequest request =
        PasswordRecoveryRequest.createNew(
            UUID.randomUUID(), account.get().accountId(), now, now.plus(RECOVERY_EXPIRATION));
    passwordRecoveryRepository.insert(request);
    Account updatedAccount = account.get().withRecoveryRequested(now);
    accountRepository.update(updatedAccount, account.get().version());

    PasswordRecoveryRequestedPayload eventPayload =
        new PasswordRecoveryRequestedPayload(request.recoveryId(), request.accountId(), account.get().personId(), request.expiresAt(), now);

    return new CommandHandlerResult(
        null, null, List.of(new EventToAppend(AuthenticationEvent.PASSWORD_RECOVERY_REQUESTED.wireName(), eventPayload)));
  }

  private boolean isPersonActive(UUID personId) {
    var status = personStatusProjectionRepository.findByPersonId(personId);
    return status.isPresent() && "ACTIVE".equals(status.get().status());
  }
}
