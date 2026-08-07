package com.estoma.users.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.application.command.DeactivatePersonCommand;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.application.port.PersonStatusHistoryRepository;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonDeactivatedPayload;
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

class DeactivatePersonHandlerTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final PersonRepository personRepository = mock(PersonRepository.class);
  private final PersonStatusHistoryRepository statusHistoryRepository = mock(PersonStatusHistoryRepository.class);
  private final DeactivatePersonHandler handler =
      new DeactivatePersonHandler(personRepository, statusHistoryRepository, codec);

  @Test
  void commandMessageTypeIsDeactivatePerson() {
    assertThat(handler.commandMessageType()).isEqualTo(MessageTypes.DEACTIVATE_PERSON);
  }

  @Test
  void deactivatesAnActivePersonAndRecordsHistory() {
    UUID personId = UUID.randomUUID();
    Person active = activePerson(personId);
    when(personRepository.findById(personId)).thenReturn(Optional.of(active));
    when(personRepository.updateStatus(any(), eq(1), eq(PersonStatus.ACTIVE))).thenReturn(1);

    HandlerResult result = handler.handle(envelopeFor(new DeactivatePersonCommand(personId, 1)));

    assertThat(result.aggregateVersion()).isEqualTo(2);
    assertThat(result.eventMessageType()).isEqualTo(MessageTypes.PERSON_DEACTIVATED);
    PersonDeactivatedPayload payload = (PersonDeactivatedPayload) result.eventPayload();
    assertThat(payload.status()).isEqualTo("INACTIVE");
    verify(statusHistoryRepository, times(1)).insert(any());
  }

  @Test
  void rejectsWhenPersonNotFound() {
    UUID personId = UUID.randomUUID();
    when(personRepository.findById(personId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> handler.handle(envelopeFor(new DeactivatePersonCommand(personId, 1))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_NOT_FOUND));
  }

  @Test
  void rejectsStaleExpectedVersion() {
    UUID personId = UUID.randomUUID();
    Person active = activePerson(personId);
    when(personRepository.findById(personId)).thenReturn(Optional.of(active));

    assertThatThrownBy(() -> handler.handle(envelopeFor(new DeactivatePersonCommand(personId, 99))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_VERSION_CONFLICT));
  }

  @Test
  void rejectsAlreadyInactivePerson() {
    UUID personId = UUID.randomUUID();
    Person inactive = activePerson(personId).deactivated(Instant.now());
    when(personRepository.findById(personId)).thenReturn(Optional.of(inactive));

    assertThatThrownBy(() -> handler.handle(envelopeFor(new DeactivatePersonCommand(personId, 2))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_ALREADY_INACTIVE));
  }

  @Test
  void reReadsToDisambiguateWhenTheGuardedUpdateAffectsZeroRows() {
    UUID personId = UUID.randomUUID();
    Person active = activePerson(personId);
    when(personRepository.findById(personId))
        .thenReturn(Optional.of(active))
        .thenReturn(Optional.empty()); // vanished between read and guarded update (defensive path)
    when(personRepository.updateStatus(any(), eq(1), eq(PersonStatus.ACTIVE))).thenReturn(0);

    assertThatThrownBy(() -> handler.handle(envelopeFor(new DeactivatePersonCommand(personId, 1))))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.PERSON_NOT_FOUND));
  }

  private static Person activePerson(UUID personId) {
    return Person.createNew(personId, "Ada", "Lovelace", null, InstitutionalEmail.of("ada@example.edu"), null, Instant.now());
  }

  private MessageEnvelope envelopeFor(DeactivatePersonCommand command) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.DEACTIVATE_PERSON,
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        "users-service",
        null,
        null,
        new Actor(UUID.randomUUID()),
        codec.toJsonNode(command));
  }
}
