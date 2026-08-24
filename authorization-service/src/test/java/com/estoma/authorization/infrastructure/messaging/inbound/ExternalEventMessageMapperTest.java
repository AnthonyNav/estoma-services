package com.estoma.authorization.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authorization.application.projection.AccountProjectionEvent;
import com.estoma.authorization.application.projection.PersonProjectionEvent;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExternalEventMessageMapperTest {

  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final ExternalEventMessageMapper eventMapper =
      new ExternalEventMessageMapper(new EnvelopeJsonCodec(mapper));

  @Test
  void acceptsPersonCreatedWithoutOptionalMaternalSurnameOrPhone() {
    UUID personId = UUID.randomUUID();
    var payload = personCreated(personId);

    var event = eventMapper.map(event(
        "PersonCreated", "users", personId, 1, new WireActor("SYSTEM", null, "users"), payload));

    assertThat(event).isInstanceOf(PersonProjectionEvent.class);
  }

  @Test
  void acceptsPersonCreatedWhenOwnerSerializerMaterializesOptionalFieldsAsNull() {
    UUID personId = UUID.randomUUID();
    var payload = personCreated(personId)
        .putNull("maternalSurname")
        .putNull("phone");

    var event = eventMapper.map(event(
        "PersonCreated", "users", personId, 1, new WireActor("SYSTEM", null, "users"), payload));

    assertThat(event).isInstanceOf(PersonProjectionEvent.class);
  }

  @Test
  void acceptsNonOtherDeactivationReasonWithNullOptionalDetail() {
    UUID accountId = UUID.randomUUID();
    var payload = mapper.createObjectNode()
        .put("accountId", accountId.toString())
        .put("personId", UUID.randomUUID().toString())
        .put("previousStatus", "ACTIVE")
        .put("status", "INACTIVE")
        .put("reasonCode", "ADMINISTRATIVE")
        .putNull("reasonDetail")
        .put("deactivatedAt", NOW.toString());

    var event = eventMapper.map(event(
        "AccountDeactivated", "authentication", accountId, 2,
        new WireActor("SYSTEM", null, "authentication"), payload));

    assertThat(event).isInstanceOf(AccountProjectionEvent.class);
  }

  @Test
  void stillRejectsNullRequiredFields() {
    UUID personId = UUID.randomUUID();
    var payload = personCreated(personId).putNull("institutionalEmail");

    assertThatThrownBy(() -> eventMapper.map(event(
            "PersonCreated", "users", personId, 1, new WireActor("SYSTEM", null, "users"), payload)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsAnUnsupportedAcademicEligibilitySystemBeforeApplication() {
    UUID eligibilityId = UUID.randomUUID();
    var payload = mapper.createObjectNode()
        .put("eligibilityId", eligibilityId.toString())
        .put("studentAcademicProfileId", UUID.randomUUID().toString())
        .put("accountId", UUID.randomUUID().toString())
        .put("systemCode", "LAVADO_ULTRASONICO")
        .put("eligible", true)
        .put("policyVersion", 1)
        .put("eligibilityVersion", 1)
        .put("academicProfileVersion", 1)
        .put("evaluatedAt", NOW.toString());

    assertThatThrownBy(() -> eventMapper.map(event(
            "StudentAcademicEligibilityChanged", "academic", eligibilityId, 1,
            new WireActor("SYSTEM", null, "academic"), payload)))
        .isInstanceOf(InvalidMessageException.class)
        .hasMessage("unsupported academic eligibility systemCode");
  }

  private tools.jackson.databind.node.ObjectNode personCreated(UUID personId) {
    return mapper.createObjectNode()
        .put("personId", personId.toString())
        .put("firstName", "Ada")
        .put("paternalSurname", "Lovelace")
        .put("institutionalEmail", "ada@estoma.mx")
        .put("status", "ACTIVE")
        .put("version", 1)
        .put("createdAt", NOW.toString());
  }

  private MessageEnvelope event(
      String type,
      String producer,
      UUID aggregateId,
      int aggregateVersion,
      WireActor actor,
      tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.EVENT, producer, NOW, UUID.randomUUID(), UUID.randomUUID(),
        null, null, aggregateId, aggregateVersion, actor, payload);
  }
}
