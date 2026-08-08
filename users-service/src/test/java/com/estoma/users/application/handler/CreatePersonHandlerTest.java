package com.estoma.users.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.estoma.users.application.DuplicatePersonPolicy;
import com.estoma.users.application.DuplicateVerdict;
import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonCreatedPayload;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CreatePersonHandlerTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final PersonRepository personRepository = mock(PersonRepository.class);
  private final DuplicatePersonPolicy duplicatePolicy = mock(DuplicatePersonPolicy.class);
  private final CreatePersonHandler handler = new CreatePersonHandler(personRepository, duplicatePolicy, codec);

  @Test
  void commandMessageTypeIsCreatePerson() {
    assertThat(handler.commandMessageType()).isEqualTo(MessageTypes.CREATE_PERSON);
  }

  @Test
  void createsAPersonAndReturnsAPersonCreatedEvent() {
    UUID personId = UUID.randomUUID();
    when(personRepository.findById(personId)).thenReturn(Optional.empty());
    when(duplicatePolicy.check("ada@example.edu", null)).thenReturn(new DuplicateVerdict.None());

    HandlerResult result =
        handler.handle(
            envelopeFor(new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada@example.edu", null)));

    assertThat(result.aggregateId()).isEqualTo(personId);
    assertThat(result.aggregateVersion()).isEqualTo(1);
    assertThat(result.eventMessageType()).isEqualTo(MessageTypes.PERSON_CREATED);
    assertThat(result.eventPayload()).isInstanceOf(PersonCreatedPayload.class);
    PersonCreatedPayload payload = (PersonCreatedPayload) result.eventPayload();
    assertThat(payload.personId()).isEqualTo(personId);
    assertThat(payload.version()).isEqualTo(1);
    assertThat(payload.status()).isEqualTo("ACTIVE");
  }

  @Test
  void rejectsWhenPersonIdAlreadyExists() {
    UUID personId = UUID.randomUUID();
    Person existing =
        Person.createNew(personId, "Ada", "Lovelace", null, com.estoma.users.domain.InstitutionalEmail.of("ada@example.edu"), null, Instant.now());
    when(personRepository.findById(personId)).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                handler.handle(
                    envelopeFor(
                        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada@example.edu", null))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_ALREADY_EXISTS));
  }

  @Test
  void rejectsBlankFirstNameAsInvalidPersonData() {
    UUID personId = UUID.randomUUID();
    when(personRepository.findById(personId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                handler.handle(
                    envelopeFor(new CreatePersonCommand(personId, "  ", "Lovelace", null, "ada@example.edu", null))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.INVALID_PERSON_DATA));
  }

  @Test
  void rejectsDuplicateInstitutionalEmail() {
    UUID personId = UUID.randomUUID();
    UUID otherPersonId = UUID.randomUUID();
    when(personRepository.findById(personId)).thenReturn(Optional.empty());
    when(duplicatePolicy.check("ada@example.edu", null))
        .thenReturn(new DuplicateVerdict.Duplicate(otherPersonId, "institutional_email_normalized"));

    assertThatThrownBy(
            () ->
                handler.handle(
                    envelopeFor(
                        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada@example.edu", null))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.DUPLICATE_PERSON));
  }

  @Test
  void personAlreadyExistsTakesPrecedenceOverDuplicateEmail() {
    UUID personId = UUID.randomUUID();
    Person existing =
        Person.createNew(personId, "Ada", "Lovelace", null, com.estoma.users.domain.InstitutionalEmail.of("ada@example.edu"), null, Instant.now());
    when(personRepository.findById(personId)).thenReturn(Optional.of(existing));
    // Even if the duplicate policy would also say duplicate, existence
    // should be checked (and win) first -- verify it's never even consulted.
    assertThatThrownBy(
            () ->
                handler.handle(
                    envelopeFor(
                        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada@example.edu", null))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_ALREADY_EXISTS));
    org.mockito.Mockito.verifyNoInteractions(duplicatePolicy);
  }

  private MessageEnvelope envelopeFor(CreatePersonCommand command) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.CREATE_PERSON,
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        command.personId(),
        null,
        new Actor.Account(UUID.randomUUID()),
        codec.toJsonNode(command));
  }
}
