package com.estoma.users.application.handler;

import com.estoma.users.application.command.ActivatePersonCommand;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.application.port.PersonStatusHistoryRepository;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonActivatedPayload;
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
 * Handles {@code ActivatePerson} (commands.md §4). Rejection precedence
 * (docs/system-contract.md §3, fixed here — not in the platform docs):
 * {@code PERSON_NOT_FOUND} &gt; {@code PERSON_VERSION_CONFLICT} &gt;
 * {@code PERSON_ALREADY_ACTIVE}. Version wins over state: if the version
 * is stale the caller must re-read regardless, so reporting "already
 * active" would send them down the wrong recovery path.
 */
@Component
class ActivatePersonHandler implements PersonCommandHandler {

  private final PersonRepository personRepository;
  private final PersonStatusHistoryRepository statusHistoryRepository;
  private final EnvelopeJsonCodec codec;

  ActivatePersonHandler(
      PersonRepository personRepository,
      PersonStatusHistoryRepository statusHistoryRepository,
      EnvelopeJsonCodec codec) {
    this.personRepository = personRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return MessageTypes.ACTIVATE_PERSON;
  }

  @Override
  public HandlerResult handle(MessageEnvelope envelope) {
    ActivatePersonCommand command = codec.convertPayload(envelope, ActivatePersonCommand.class);

    Person existing =
        personRepository
            .findById(command.personId())
            .orElseThrow(
                () -> new PersonRejectedException(RejectionCode.PERSON_NOT_FOUND, "person not found"));

    if (existing.version() != command.expectedVersion()) {
      throw new PersonRejectedException(RejectionCode.PERSON_VERSION_CONFLICT, "expectedVersion is stale");
    }

    // Person.activated() throws PERSON_ALREADY_ACTIVE if already ACTIVE.
    Person activated = existing.activated(Instant.now());

    int rowsAffected =
        personRepository.updateStatus(activated, command.expectedVersion(), PersonStatus.INACTIVE);
    if (rowsAffected == 0) {
      throw disambiguateGuardFailure(command.personId(), command.expectedVersion());
    }

    UUID changedByAccountId = envelope.actor() == null ? null : envelope.actor().accountId();
    statusHistoryRepository.insert(
        new PersonStatusChange(
            UUID.randomUUID(),
            activated.personId(),
            PersonStatus.INACTIVE,
            PersonStatus.ACTIVE,
            null, // reasonCode/reasonDetail only apply to a deactivation
            null,
            changedByAccountId,
            envelope.correlationId(),
            activated.updatedAt()));

    PersonActivatedPayload eventPayload =
        new PersonActivatedPayload(
            activated.personId(), activated.status().name(), activated.version(), activated.updatedAt());

    return new HandlerResult(
        activated.personId(), activated.version(), eventPayload, MessageTypes.PERSON_ACTIVATED);
  }

  private PersonRejectedException disambiguateGuardFailure(UUID personId, int expectedVersion) {
    Optional<Person> reread = personRepository.findById(personId);
    if (reread.isEmpty()) {
      return new PersonRejectedException(RejectionCode.PERSON_NOT_FOUND, "person not found");
    }
    if (reread.get().version() != expectedVersion) {
      return new PersonRejectedException(RejectionCode.PERSON_VERSION_CONFLICT, "expectedVersion is stale");
    }
    return new PersonRejectedException(RejectionCode.PERSON_ALREADY_ACTIVE, "person is already active");
  }
}
