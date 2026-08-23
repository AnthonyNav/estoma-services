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
    validator.validateEvent(event("PersonCreated", "users", personId,
        MAPPER.createObjectNode().put("personId", personId.toString()).put("status", "ACTIVE")));
  }

  @Test
  void rejectsSpoofedPersonProducer() {
    UUID personId = UUID.randomUUID();
    assertThatThrownBy(() -> validator.validateEvent(event("PersonCreated", "authentication", personId,
        MAPPER.createObjectNode().put("personId", personId.toString()).put("status", "ACTIVE"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAggregatePayloadMismatch() {
    UUID personId = UUID.randomUUID();
    assertThatThrownBy(() -> validator.validateEvent(event("PersonCreated", "users", UUID.randomUUID(),
        MAPPER.createObjectNode().put("personId", personId.toString()).put("status", "ACTIVE"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsPasswordRecoveryUntilTheSecureAdapterLands() {
    UUID recoveryId = UUID.randomUUID();
    assertThatThrownBy(() -> validator.validateEvent(event("PasswordRecoveryRequested", "authentication", recoveryId,
        MAPPER.createObjectNode().put("recoveryId", recoveryId.toString()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MessageEnvelope event(String type, String producer, UUID aggregateId, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.EVENT, producer, Instant.now(), UUID.randomUUID(), null,
        null, null, aggregateId, 1, Actor.system(producer), payload);
  }
}
