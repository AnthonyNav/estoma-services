package com.estoma.authentication.application.command;

import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.SecureRandomGenerator;
import com.estoma.authentication.application.port.TokenHasher;
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

  private static final int CODE_DIGITS = 6;
  private static final java.time.Duration RECOVERY_EXPIRATION = java.time.Duration.ofMinutes(30);

  private final AccountRepository accountRepository;
  private final PasswordRecoveryRepository passwordRecoveryRepository;
  private final SecureRandomGenerator randomGenerator;
  private final TokenHasher tokenHasher;
  private final EnvelopeJsonCodec codec;

  RequestPasswordRecoveryHandler(
      AccountRepository accountRepository,
      PasswordRecoveryRepository passwordRecoveryRepository,
      SecureRandomGenerator randomGenerator,
      TokenHasher tokenHasher,
      EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.passwordRecoveryRepository = passwordRecoveryRepository;
    this.randomGenerator = randomGenerator;
    this.tokenHasher = tokenHasher;
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
    Optional<Account> account = accountRepository.findByLoginIdentifier(identifier.type(), identifier.normalized());

    if (account.isEmpty() || !account.get().isActive()) {
      return new CommandHandlerResult(null, null, List.of());
    }

    Instant now = Instant.now();
    String code = randomGenerator.randomNumericCode(CODE_DIGITS);
    PasswordRecoveryRequest request =
        PasswordRecoveryRequest.createNew(UUID.randomUUID(), account.get().accountId(), tokenHasher.sha256Hex(code), now, now.plus(RECOVERY_EXPIRATION));
    passwordRecoveryRepository.insert(request);

    PasswordRecoveryRequestedPayload eventPayload =
        new PasswordRecoveryRequestedPayload(request.recoveryId(), request.accountId(), account.get().personId(), request.expiresAt(), now);

    return new CommandHandlerResult(
        null, null, List.of(new EventToAppend(AuthenticationEvent.PASSWORD_RECOVERY_REQUESTED.wireName(), eventPayload)));
  }
}
