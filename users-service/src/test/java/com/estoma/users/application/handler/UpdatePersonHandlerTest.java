package com.estoma.users.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.estoma.users.application.DuplicatePersonPolicy;
import com.estoma.users.application.DuplicateVerdict;
import com.estoma.users.application.command.UpdatePersonCommand;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonUpdatedPayload;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class UpdatePersonHandlerTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final PersonRepository personRepository = mock(PersonRepository.class);
  private final DuplicatePersonPolicy duplicatePolicy = mock(DuplicatePersonPolicy.class);
  private final UpdatePersonHandler handler = new UpdatePersonHandler(personRepository, duplicatePolicy, codec);

  @Test
  void commandMessageTypeIsUpdatePerson() {
    assertThat(handler.commandMessageType()).isEqualTo(MessageTypes.UPDATE_PERSON);
  }

  @Test
  void updatesAPersonAndReturnsAPersonUpdatedEvent() {
    UUID personId = UUID.randomUUID();
    Person existing = existingPerson(personId, 1);
    when(personRepository.findById(personId)).thenReturn(Optional.of(existing));
    when(duplicatePolicy.check("augusta@example.edu", personId)).thenReturn(new DuplicateVerdict.None());
    when(personRepository.updateProfile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1)))
        .thenReturn(1);

    HandlerResult result =
        handler.handle(
            envelopeFor(new UpdatePersonCommand(personId, 1, "Augusta", "Lovelace", null, "augusta@example.edu", null)));

    assertThat(result.aggregateVersion()).isEqualTo(2);
    assertThat(result.eventMessageType()).isEqualTo(MessageTypes.PERSON_UPDATED);
    PersonUpdatedPayload payload = (PersonUpdatedPayload) result.eventPayload();
    assertThat(payload.firstName()).isEqualTo("Augusta");
  }

  @Test
  void rejectsWhenPersonNotFound() {
    UUID personId = UUID.randomUUID();
    when(personRepository.findById(personId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                handler.handle(
                    envelopeFor(
                        new UpdatePersonCommand(personId, 1, "Ada", "Lovelace", null, "ada@example.edu", null))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_NOT_FOUND));
  }

  @Test
  void rejectsStaleExpectedVersionBeforeValidatingFields() {
    UUID personId = UUID.randomUUID();
    Person existing = existingPerson(personId, 3);
    when(personRepository.findById(personId)).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                // expectedVersion=1 is stale (actual is 3); also passes a
                // blank firstName to prove version-conflict is checked first.
                handler.handle(
                    envelopeFor(new UpdatePersonCommand(personId, 1, "   ", "Lovelace", null, "ada@example.edu", null))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_VERSION_CONFLICT));
  }

  @Test
  void rejectsDuplicateEmailFromADifferentPerson() {
    UUID personId = UUID.randomUUID();
    UUID otherPersonId = UUID.randomUUID();
    Person existing = existingPerson(personId, 1);
    when(personRepository.findById(personId)).thenReturn(Optional.of(existing));
    when(duplicatePolicy.check("taken@example.edu", personId))
        .thenReturn(new DuplicateVerdict.Duplicate(otherPersonId, "institutional_email_normalized"));

    assertThatThrownBy(
            () ->
                handler.handle(
                    envelopeFor(
                        new UpdatePersonCommand(personId, 1, "Ada", "Lovelace", null, "taken@example.edu", null))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.DUPLICATE_PERSON));
  }

  @Test
  void reReadsToDisambiguateWhenTheGuardedUpdateAffectsZeroRowsDueToARace() {
    UUID personId = UUID.randomUUID();
    Person existing = existingPerson(personId, 1);
    when(personRepository.findById(personId))
        .thenReturn(Optional.of(existing)) // first read: version matches
        .thenReturn(Optional.of(existing.withUpdatedProfile("Someone", "Else", null, existing.email(), null, Instant.now()))); // re-read after lost race: version has moved on
    when(duplicatePolicy.check("ada@example.edu", personId)).thenReturn(new DuplicateVerdict.None());
    when(personRepository.updateProfile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1)))
        .thenReturn(0); // another writer won the race

    assertThatThrownBy(
            () ->
                handler.handle(
                    envelopeFor(
                        new UpdatePersonCommand(personId, 1, "Ada", "Lovelace", null, "ada@example.edu", null))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_VERSION_CONFLICT));
  }

  private static Person existingPerson(UUID personId, int version) {
    Person person =
        Person.createNew(personId, "Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now());
    for (int i = 1; i < version; i++) {
      person = person.withUpdatedProfile(person.firstName(), person.paternalSurname(), person.maternalSurname(), person.email(), person.phone(), Instant.now());
    }
    return person;
  }

  private MessageEnvelope envelopeFor(UpdatePersonCommand command) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.UPDATE_PERSON,
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new Actor.Account(UUID.randomUUID()),
        codec.toJsonNode(command));
  }
}
