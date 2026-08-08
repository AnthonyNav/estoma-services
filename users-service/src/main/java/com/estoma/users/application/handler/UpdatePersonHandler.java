package com.estoma.users.application.handler;

import com.estoma.users.application.DuplicatePersonPolicy;
import com.estoma.users.application.DuplicateVerdict;
import com.estoma.users.application.command.UpdatePersonCommand;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonUpdatedPayload;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Handles {@code UpdatePerson} (commands.md §3). Rejection precedence
 * (docs/system-contract.md §3): {@code PERSON_NOT_FOUND} &gt; {@code
 * PERSON_VERSION_CONFLICT} &gt; {@code INVALID_PERSON_DATA} &gt; {@code
 * DUPLICATE_PERSON}. Never touches {@code status} or {@code
 * deactivatedAt} (commands.md:117).
 */
@Component
class UpdatePersonHandler implements PersonCommandHandler {

  private final PersonRepository personRepository;
  private final DuplicatePersonPolicy duplicatePolicy;
  private final EnvelopeJsonCodec codec;

  UpdatePersonHandler(
      PersonRepository personRepository, DuplicatePersonPolicy duplicatePolicy, EnvelopeJsonCodec codec) {
    this.personRepository = personRepository;
    this.duplicatePolicy = duplicatePolicy;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return MessageTypes.UPDATE_PERSON;
  }

  @Override
  public HandlerResult handle(MessageEnvelope envelope) {
    UpdatePersonCommand command = codec.convertPayload(envelope, UpdatePersonCommand.class);

    Person existing =
        personRepository
            .findById(command.personId())
            .orElseThrow(
                () -> new PersonRejectedException(RejectionCode.PERSON_NOT_FOUND, "person not found"));

    if (existing.version() != command.expectedVersion()) {
      throw new PersonRejectedException(RejectionCode.PERSON_VERSION_CONFLICT, "expectedVersion is stale");
    }

    boolean academicImport = "academic".equals(envelope.producer());
    if (academicImport && !normalize(command.institutionalEmail()).equals(existing.email().normalized())) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_MESSAGE,
          "academic UpdatePerson institutionalEmail must match the existing person");
    }
    if (academicImport && command.phone() != null) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_MESSAGE, "academic UpdatePerson must not include phone");
    }

    InstitutionalEmail email = academicImport ? existing.email() : InstitutionalEmail.of(command.institutionalEmail());
    String phone = academicImport ? existing.phone() : command.phone();

    if (duplicatePolicy.check(email.normalized(), command.personId())
        instanceof DuplicateVerdict.Duplicate) {
      throw new PersonRejectedException(
          RejectionCode.DUPLICATE_PERSON,
          "institutional email is already claimed by a different person");
    }

    Person updated =
        existing.withUpdatedProfile(
            command.firstName(),
            command.paternalSurname(),
            command.maternalSurname(),
            email,
            phone,
            Instant.now());

    int rowsAffected = personRepository.updateProfile(updated, command.expectedVersion());
    if (rowsAffected == 0) {
      throw disambiguateGuardFailure(command.personId(), command.expectedVersion());
    }

    PersonUpdatedPayload eventPayload =
        new PersonUpdatedPayload(
            updated.personId(),
            updated.firstName(),
            updated.paternalSurname(),
            updated.maternalSurname(),
            updated.email().value(),
            updated.phone(),
            updated.version(),
            updated.updatedAt());

    return new HandlerResult(updated.personId(), updated.version(), eventPayload, MessageTypes.PERSON_UPDATED);
  }

  /**
   * {@code rowsAffected == 0} means either the person vanished or the
   * version guard failed between the read above and the guarded update —
   * a genuine race, not the common path. Version wins over "not found" in
   * the precedence order per docs/system-contract.md §3.
   */
  private PersonRejectedException disambiguateGuardFailure(java.util.UUID personId, int expectedVersion) {
    Optional<Person> reread = personRepository.findById(personId);
    if (reread.isEmpty()) {
      return new PersonRejectedException(RejectionCode.PERSON_NOT_FOUND, "person not found");
    }
    return new PersonRejectedException(RejectionCode.PERSON_VERSION_CONFLICT, "expectedVersion is stale");
  }

  private static String normalize(String value) {
    return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
  }
}
