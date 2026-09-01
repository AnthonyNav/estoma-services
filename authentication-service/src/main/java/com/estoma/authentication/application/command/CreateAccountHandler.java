package com.estoma.authentication.application.command;

import com.estoma.authentication.application.DeferredAcademicCommandException;
import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.AccountStatusHistoryRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.command.CreateAccountCommand;
import com.estoma.authentication.contracts.event.AccountCreatedPayload;
import com.estoma.authentication.contracts.event.AccountPresentationUpdatedPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.AccountStatusChange;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class CreateAccountHandler implements CommandHandler {

  private final AccountRepository accountRepository;
  private final PersonStatusProjectionRepository personStatusProjectionRepository;
  private final AccountStatusHistoryRepository statusHistoryRepository;
  private final EnvelopeJsonCodec codec;

  CreateAccountHandler(
      AccountRepository accountRepository,
      PersonStatusProjectionRepository personStatusProjectionRepository,
      AccountStatusHistoryRepository statusHistoryRepository,
      EnvelopeJsonCodec codec) {
    this.accountRepository = accountRepository;
    this.personStatusProjectionRepository = personStatusProjectionRepository;
    this.statusHistoryRepository = statusHistoryRepository;
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
      String pendingReason = academicPendingReason(envelope, command.personId());
      if (pendingReason != null) {
        throw new DeferredAcademicCommandException(pendingReason);
      }
      throw new AuthenticationRejectedException(
          RejectionCode.PERSON_NOT_FOUND, "person " + command.personId() + " is not known to this service");
    }
    if (!"ACTIVE".equals(personStatus.get().status())) {
      throw new AuthenticationRejectedException(
          RejectionCode.PERSON_INACTIVE, "person " + command.personId() + " is not active");
    }

    Instant now = Instant.now();
    LoginIdentifier loginIdentifier = LoginIdentifier.of(command.loginType(), command.loginIdentifier());
    Account account = Account.createNew(command.accountId(), command.personId(), loginIdentifier, now);
    accountRepository.insert(account);
    statusHistoryRepository.insert(new AccountStatusChange(
        UUID.randomUUID(), account.accountId(), null, account.status(), null, null,
        envelope.actor(), envelope.correlationId(), now, account.version()));

    AccountCreatedPayload eventPayload = new AccountCreatedPayload(
        account.accountId(), account.personId(), account.loginIdentifier().type(),
        account.status().name(), account.createdAt());
    AccountPresentationUpdatedPayload presentation = presentation(account);
    return CommandHandlerResult.of(
        account.accountId(), account.version(),
        new EventToAppend(AuthenticationEvent.ACCOUNT_CREATED.wireName(), eventPayload),
        new EventToAppend(AuthenticationEvent.ACCOUNT_PRESENTATION_UPDATED.wireName(), presentation));
  }

  private static AccountPresentationUpdatedPayload presentation(Account account) {
    return new AccountPresentationUpdatedPayload(
        account.accountId(), account.personId(), account.loginIdentifier().type(),
        account.loginIdentifier().value(), account.status().name(), account.updatedAt());
  }

  private static String academicPendingReason(MessageEnvelope envelope, UUID personId) {
    if (!"academic".equals(envelope.producer())) {
      return null;
    }
    JsonNode execution = envelope.payload().path("importExecution");
    JsonNode dependencies = execution.path("causalDependencies");
    if (dependencies.isArray()) {
      for (JsonNode dependency : dependencies) {
        String expectedEvent = dependency.path("expectedEventType").asString();
        if ("SUCCEEDED".equals(dependency.path("priorResultStatus").asString())
            && ("PersonCreated".equals(expectedEvent) || "PersonActivated".equals(expectedEvent))
            && personId.toString().equals(dependency.path("expectedAggregateId").asString())) {
          return "EVENT_PENDING";
        }
      }
    }
    JsonNode evidence = execution.path("authoritativeEvidence");
    if (evidence.isArray()) {
      for (JsonNode item : evidence) {
        String sourceStatus = item.path("sourceResultStatus").asString();
        if ("users".equals(item.path("sourceService").asString())
            && ("FOUND".equals(sourceStatus) || "SUCCEEDED".equals(sourceStatus))
            && "Person".equals(item.path("entityType").asString())
            && personId.toString().equals(item.path("entityId").asString())) {
          return "PROJECTION_RECONCILIATION";
        }
      }
    }
    return null;
  }
}
