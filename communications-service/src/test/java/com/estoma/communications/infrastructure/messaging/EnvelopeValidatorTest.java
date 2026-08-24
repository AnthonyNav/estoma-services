package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EnvelopeValidatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final EnvelopeValidator validator = new EnvelopeValidator();

  @Test
  void acceptsCanonicalUsersPersonEvent() {
    UUID personId = UUID.randomUUID();
    validator.validateEvent(event("PersonCreated", "users", personId, 1,
        MAPPER.createObjectNode().put("personId", personId.toString()).put("status", "ACTIVE")));
  }

  @Test
  void rejectsSpoofedPersonProducer() {
    UUID personId = UUID.randomUUID();
    assertThatThrownBy(() -> validator.validateEvent(event("PersonCreated", "authentication", personId, 1,
        MAPPER.createObjectNode().put("personId", personId.toString()).put("status", "ACTIVE"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAggregatePayloadMismatch() {
    UUID personId = UUID.randomUUID();
    assertThatThrownBy(() -> validator.validateEvent(event("PersonCreated", "users", UUID.randomUUID(), 1,
        MAPPER.createObjectNode().put("personId", personId.toString()).put("status", "ACTIVE"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAuthenticationRecoveryFactWithoutAggregateIdentity() {
    UUID recoveryId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    validator.validateEvent(event("PasswordRecoveryRequested", "authentication", null, null,
        MAPPER.createObjectNode()
            .put("recoveryId", recoveryId.toString())
            .put("accountId", accountId.toString())
            .put("personId", personId.toString())
            .put("expiresAt", Instant.now().plusSeconds(600).toString())
            .put("requestedAt", Instant.now().toString())));
  }

  @Test
  void rejectsSpoofedRecoveryProducer() {
    UUID recoveryId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    assertThatThrownBy(() -> validator.validateEvent(event("PasswordRecoveryRequested", "users", null, null,
        MAPPER.createObjectNode()
            .put("recoveryId", recoveryId.toString())
            .put("accountId", accountId.toString())
            .put("personId", personId.toString()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MessageEnvelope event(
      String type, String producer, UUID aggregateId, Integer aggregateVersion, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.EVENT, producer, Instant.now(), UUID.randomUUID(), null,
        null, null, aggregateId, aggregateVersion, Actor.system(producer), payload);
  }
}
