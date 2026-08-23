package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuthorizationRecoveryQueryTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final PersonRepository persons = mock(PersonRepository.class);
  private final OutboxPort outbox = mock(OutboxPort.class);
  private final PersonQueryDispatcher dispatcher = new PersonQueryDispatcher(persons, outbox, codec);

  @Test
  void pointSnapshotReturnsOnlyAuthorizationState() {
    UUID personId = UUID.randomUUID();
    Person person = person(personId);
    when(persons.findById(personId)).thenReturn(java.util.Optional.of(person));

    dispatcher.resolve(query(
        MessageTypes.GET_PERSON_STATUS_SNAPSHOT,
        java.util.Map.of("personId", personId.toString())));

    JsonNode payload = codec.parseJson(captured().payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("FOUND");
    assertThat(payload.path("data").path("personId").asString()).isEqualTo(personId.toString());
    assertThat(payload.path("data").path("status").asString()).isEqualTo("ACTIVE");
    assertThat(payload.path("data").path("sourceVersion").asInt()).isEqualTo(1);
    assertThat(payload.path("data").has("firstName")).isFalse();
    assertThat(payload.path("data").has("institutionalEmail")).isFalse();
  }

  @Test
  void missingPointReturnsNotFoundWithoutData() {
    UUID personId = UUID.randomUUID();
    when(persons.findById(personId)).thenReturn(java.util.Optional.empty());

    dispatcher.resolve(query(
        MessageTypes.GET_PERSON_STATUS_SNAPSHOT,
        java.util.Map.of("personId", personId.toString())));

    JsonNode payload = codec.parseJson(captured().payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("NOT_FOUND");
    assertThat(payload.has("data")).isFalse();
  }

  @Test
  void pageUsesStableUuidCursorAndLimitPlusOne() {
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000101");
    UUID second = UUID.fromString("00000000-0000-0000-0000-000000000102");
    when(persons.listAfter(null, 201)).thenReturn(List.of(person(first), person(second)));

    dispatcher.resolve(query(
        MessageTypes.LIST_PERSON_STATUS_SNAPSHOTS_PAGE,
        java.util.Map.of("limit", 200)));

    verify(persons).listAfter(null, 201);
    JsonNode payload = codec.parseJson(captured().payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(payload.path("data").path("items").size()).isEqualTo(2);
    assertThat(payload.path("data").path("hasMore").asBoolean()).isFalse();
    assertThat(payload.path("data").has("nextCursor")).isFalse();
  }

  private MessageEnvelope query(String type, Object payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.QUERY, "authorization", Instant.now(),
        UUID.randomUUID(), null, UUID.randomUUID(), null, null, null,
        new Actor.System("authorization"), codec.toJsonNode(payload));
  }

  private OutboxAppendRequest captured() {
    ArgumentCaptor<OutboxAppendRequest> captor = ArgumentCaptor.forClass(OutboxAppendRequest.class);
    verify(outbox).append(captor.capture());
    return captor.getValue();
  }

  private static Person person(UUID id) {
    return Person.createNew(
        id, "Ada", "Lovelace", null, InstitutionalEmail.of(id + "@estoma.mx"), null,
        Instant.parse("2026-08-01T00:00:00Z"));
  }
}
