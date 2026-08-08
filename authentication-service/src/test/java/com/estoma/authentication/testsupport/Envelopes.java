package com.estoma.authentication.testsupport;

import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Builds {@link MessageEnvelope}s for dispatcher tests, without any RabbitMQ involved. */
public final class Envelopes {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Envelopes() {}

  public static MessageEnvelope command(String messageType, Object payload) {
    return command(messageType, payload, UUID.randomUUID());
  }

  public static MessageEnvelope command(String messageType, Object payload, UUID messageId) {
    JsonNode payloadNode = MAPPER.valueToTree(payload);
    return new MessageEnvelope(
        messageId, messageType, 1, MessageKind.COMMAND, "bff", Instant.now(), UUID.randomUUID(), null, UUID.randomUUID(),
        "authentication-service", null, null, new Actor(UUID.randomUUID()), payloadNode);
  }

  public static MessageEnvelope event(String messageType, Object payload, UUID aggregateId, int aggregateVersion) {
    return event(messageType, payload, aggregateId, aggregateVersion, UUID.randomUUID());
  }

  public static MessageEnvelope event(String messageType, Object payload, UUID aggregateId, int aggregateVersion, UUID messageId) {
    JsonNode payloadNode = MAPPER.valueToTree(payload);
    return new MessageEnvelope(
        messageId, messageType, 1, MessageKind.EVENT, "users-service", Instant.now(), UUID.randomUUID(), null, null,
        null, aggregateId, aggregateVersion, null, payloadNode);
  }
}
