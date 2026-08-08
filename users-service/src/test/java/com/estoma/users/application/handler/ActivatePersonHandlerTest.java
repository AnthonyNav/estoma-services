package com.estoma.users.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.application.command.ActivatePersonCommand;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.application.port.PersonStatusHistoryRepository;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonActivatedPayload;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.PersonStatus;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ActivatePersonHandlerTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final PersonRepository personRepository = mock(PersonRepository.class);
  private final PersonStatusHistoryRepository statusHistoryRepository = mock(PersonStatusHistoryRepository.class);
  private final ActivatePersonHandler handler =
      new ActivatePersonHandler(personRepository, statusHistoryRepository, codec);

  @Test
  void commandMessageTypeIsActivatePerson() {
    assertThat(handler.commandMessageType()).isEqualTo(MessageTypes.ACTIVATE_PERSON);
  }

  @Test
  void activatesAnInactivePersonAndRecordsHistory() {
    // Person.createNew starts at version 1, ACTIVE; deactivated() bumps
    // it to version 2 -- there is no domain path to an INACTIVE person at
    // version 1, so the "existing" state here is deliberately version 2.
    UUID personId = UUID.randomUUID();
    Person inactive = deactivatedOnce(personId);
    when(personRepository.findById(personId)).thenReturn(Optional.of(inactive));
    when(personRepository.updateStatus(any(), eq(2), eq(PersonStatus.INACTIVE))).thenReturn(1);

    HandlerResult result = handler.handle(envelopeFor(new ActivatePersonCommand(personId, 2)));

    assertThat(result.aggregateVersion()).isEqualTo(3);
    assertThat(result.eventMessageType()).isEqualTo(MessageTypes.PERSON_ACTIVATED);
    PersonActivatedPayload payload = (PersonActivatedPayload) result.eventPayload();
    assertThat(payload.status()).isEqualTo("ACTIVE");
    verify(statusHistoryRepository, times(1)).insert(any());
  }

  @Test
  void rejectsWhenPersonNotFound() {
    UUID personId = UUID.randomUUID();
    when(personRepository.findById(personId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> handler.handle(envelopeFor(new ActivatePersonCommand(personId, 1))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_NOT_FOUND));
  }

  @Test
  void rejectsStaleExpectedVersion() {
    UUID personId = UUID.randomUUID();
    Person inactive = deactivatedOnce(personId); // version 2
    when(personRepository.findById(personId)).thenReturn(Optional.of(inactive));

    assertThatThrownBy(() -> handler.handle(envelopeFor(new ActivatePersonCommand(personId, 1))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_VERSION_CONFLICT));
  }

  @Test
  void rejectsAlreadyActivePerson() {
    UUID personId = UUID.randomUUID();
    Person active =
        Person.createNew(personId, "Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now());
    when(personRepository.findById(personId)).thenReturn(Optional.of(active));

    assertThatThrownBy(() -> handler.handle(envelopeFor(new ActivatePersonCommand(personId, 1))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_ALREADY_ACTIVE));
  }

  @Test
  void versionConflictTakesPrecedenceOverAlreadyActiveWhenBothWouldApply() {
    // existing is version 5 and ACTIVE; command still claims expectedVersion=1.
    // Even though re-checking "already active" might also seem to apply,
    // the version guard must fire first (docs/system-contract.md §3).
    UUID personId = UUID.randomUUID();
    Person active =
        Person.createNew(personId, "Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now())
            .withUpdatedProfile("Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now())
            .withUpdatedProfile("Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now())
            .withUpdatedProfile("Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now())
            .withUpdatedProfile("Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now());
    when(personRepository.findById(personId)).thenReturn(Optional.of(active));

    assertThatThrownBy(() -> handler.handle(envelopeFor(new ActivatePersonCommand(personId, 1))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_VERSION_CONFLICT));
  }

  private static Person deactivatedOnce(UUID personId) {
    Person person =
        Person.createNew(personId, "Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now());
    return person.deactivated(Instant.now());
  }

  private MessageEnvelope envelopeFor(ActivatePersonCommand command) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.ACTIVATE_PERSON,
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
