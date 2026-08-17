package com.estoma.washadministration.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessageKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EnvelopeValidatorTest {

  private final EnvelopeValidator validator = new EnvelopeValidator();

  @Test
  void knownButUnauthorizedQueryRequesterReachesFunctionalAcl() {
    validator.validateConfigurationQuery(
        envelope(MessageKind.QUERY, "GetWashAdministrationConfiguration", "bff", Actor.system("bff")));
  }

  @Test
  void queryActorMustMatchTechnicalProducer() {
    assertThatThrownBy(
            () ->
                validator.validateConfigurationQuery(
                    envelope(
                        MessageKind.QUERY,
                        "GetWashAdministrationConfiguration",
                        "bff",
                        Actor.system("academic"))))
        .isInstanceOf(InvalidMessageException.class)
        .hasMessage("QUERY_ACTOR_NOT_ALLOWED");
  }

  @Test
  void actorCannotMixAccountAndSystemFields() {
    Actor malformed = new Actor("SYSTEM", UUID.randomUUID(), "authorization");

    assertThatThrownBy(
            () ->
                validator.validateAuthorizationEvent(
                    envelope(MessageKind.EVENT, "AccountRoleAssigned", "authorization", malformed)))
        .isInstanceOf(InvalidMessageException.class)
        .hasMessage("SYSTEM_ACTOR_ACCOUNT_ID_NOT_ALLOWED");
  }

  private static MessageEnvelope envelope(
      MessageKind kind, String messageType, String producer, Actor actor) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        messageType,
        1,
        kind,
        producer,
        Instant.parse("2026-01-01T00:00:00Z"),
        UUID.randomUUID(),
        null,
        null,
        null,
        UUID.randomUUID(),
        1,
        actor,
        new ObjectMapper().createObjectNode());
  }
}
