package com.estoma.users.application.handler;

import com.estoma.users.application.command.DeactivatePersonCommand;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.application.port.PersonStatusHistoryRepository;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonDeactivatedPayload;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.PersonStatus;
import com.estoma.users.domain.PersonStatusChange;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@code DeactivatePerson} (commands.md §5). Rejection precedence
 * (docs/system-contract.md §3): {@code PERSON_NOT_FOUND} &gt; {@code
 * PERSON_VERSION_CONFLICT} &gt; {@code PERSON_ALREADY_INACTIVE}.
 */
@Component
class DeactivatePersonHandler implements PersonCommandHandler {

  private final PersonRepository personRepository;
  private final PersonStatusHistoryRepository statusHistoryRepository;
  private final EnvelopeJsonCodec codec;

  DeactivatePersonHandler(
      PersonRepository personRepository,
      PersonStatusHistoryRepository statusHistoryRepository,
      EnvelopeJsonCodec codec) {
    this.personRepository = personRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return MessageTypes.DEACTIVATE_PERSON;
  }

  @Override
  public HandlerResult handle(MessageEnvelope envelope) {
    DeactivatePersonCommand command = codec.convertPayload(envelope, DeactivatePersonCommand.class);

    Person existing =
        personRepository
            .findById(command.personId())
            .orElseThrow(
                () -> new PersonRejectedException(RejectionCode.PERSON_NOT_FOUND, "person not found"));

    if (existing.version() != command.expectedVersion()) {
      throw new PersonRejectedException(RejectionCode.PERSON_VERSION_CONFLICT, "expectedVersion is stale");
    }

    // Person.deactivated() throws PERSON_ALREADY_INACTIVE if already INACTIVE.
    Person deactivated = existing.deactivated(Instant.now());

    int rowsAffected =
        personRepository.updateStatus(deactivated, command.expectedVersion(), PersonStatus.ACTIVE);
    if (rowsAffected == 0) {
      throw disambiguateGuardFailure(command.personId(), command.expectedVersion());
    }

    UUID changedByAccountId = envelope.actor() == null ? null : envelope.actor().accountId();
    statusHistoryRepository.insert(
        new PersonStatusChange(
            UUID.randomUUID(),
            deactivated.personId(),
            PersonStatus.ACTIVE,
            PersonStatus.INACTIVE,
            changedByAccountId,
            envelope.correlationId(),
            deactivated.updatedAt()));

    PersonDeactivatedPayload eventPayload =
        new PersonDeactivatedPayload(
            deactivated.personId(),
            deactivated.status().name(),
            deactivated.version(),
            deactivated.updatedAt());

    return new HandlerResult(
        deactivated.personId(), deactivated.version(), eventPayload, MessageTypes.PERSON_DEACTIVATED);
  }

  private PersonRejectedException disambiguateGuardFailure(UUID personId, int expectedVersion) {
    Optional<Person> reread = personRepository.findById(personId);
    if (reread.isEmpty()) {
      return new PersonRejectedException(RejectionCode.PERSON_NOT_FOUND, "person not found");
    }
    if (reread.get().version() != expectedVersion) {
      return new PersonRejectedException(RejectionCode.PERSON_VERSION_CONFLICT, "expectedVersion is stale");
    }
    return new PersonRejectedException(RejectionCode.PERSON_ALREADY_INACTIVE, "person is already inactive");
  }
}
