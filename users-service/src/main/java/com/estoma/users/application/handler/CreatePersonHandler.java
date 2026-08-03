package com.estoma.users.application.handler;

import com.estoma.users.application.DuplicatePersonPolicy;
import com.estoma.users.application.DuplicateVerdict;
import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonCreatedPayload;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code CreatePerson} (commands.md §2). Rejection precedence
 * (docs/system-contract.md §3): {@code PERSON_ALREADY_EXISTS} &gt;
 * {@code INVALID_PERSON_DATA} &gt; {@code DUPLICATE_PERSON}.
 */
@Component
class CreatePersonHandler implements PersonCommandHandler {

  private final PersonRepository personRepository;
  private final DuplicatePersonPolicy duplicatePolicy;
  private final EnvelopeJsonCodec codec;

  CreatePersonHandler(
      PersonRepository personRepository, DuplicatePersonPolicy duplicatePolicy, EnvelopeJsonCodec codec) {
    this.personRepository = personRepository;
    this.duplicatePolicy = duplicatePolicy;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return MessageTypes.CREATE_PERSON;
  }

  @Override
  public HandlerResult handle(MessageEnvelope envelope) {
    CreatePersonCommand command = codec.convertPayload(envelope, CreatePersonCommand.class);

    if (personRepository.findById(command.personId()).isPresent()) {
      throw new PersonRejectedException(
          RejectionCode.PERSON_ALREADY_EXISTS, "a person with this personId already exists");
    }

    // InstitutionalEmail.of(...) and Person.createNew(...) throw
    // PersonRejectedException(INVALID_PERSON_DATA) for blank/malformed
    // fields -- letting it propagate is the correct behavior here.
    InstitutionalEmail email = InstitutionalEmail.of(command.institutionalEmail());

    if (duplicatePolicy.check(email.normalized(), null) instanceof DuplicateVerdict.Duplicate) {
      throw new PersonRejectedException(
          RejectionCode.DUPLICATE_PERSON,
          "institutional email is already claimed by a different person");
    }

    Instant now = Instant.now();
    Person person =
        Person.createNew(
            command.personId(),
            command.firstName(),
            command.paternalSurname(),
            command.maternalSurname(),
            email,
            command.phone(),
            now);

    // May itself throw PersonRejectedException on a lost race against a
    // concurrent writer (see JdbcPersonRepository#insert).
    personRepository.insert(person);

    PersonCreatedPayload eventPayload =
        new PersonCreatedPayload(
            person.personId(),
            person.firstName(),
            person.paternalSurname(),
            person.maternalSurname(),
            person.email().value(),
            person.phone(),
            person.status().name(),
            person.version(),
            person.createdAt());

    return new HandlerResult(person.personId(), person.version(), eventPayload, MessageTypes.PERSON_CREATED);
  }
}
