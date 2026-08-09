package com.estoma.communications.testsupport;

import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Builds {@link MessageEnvelope}s for dispatcher tests, without any RabbitMQ involved. */
public final class Envelopes {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Envelopes() {}

  public static MessageEnvelope command(String messageType, Object payload) {
    return command(messageType, payload, UUID.randomUUID(), UUID.randomUUID());
  }

  public static MessageEnvelope command(String messageType, Object payload, UUID messageId) {
    return command(messageType, payload, messageId, UUID.randomUUID());
  }

  public static MessageEnvelope command(String messageType, Object payload, UUID messageId, UUID idempotencyKey) {
    JsonNode payloadNode = MAPPER.valueToTree(payload);
    return new MessageEnvelope(
         messageId, messageType, 1, MessageKind.COMMAND, "bff", Instant.now(), UUID.randomUUID(), null, UUID.randomUUID(), idempotencyKey, null, null,
        Actor.system("bff"), payloadNode);
  }

  public static MessageEnvelope event(String messageType, Object payload, UUID aggregateId, int aggregateVersion) {
    return event(messageType, payload, aggregateId, aggregateVersion, UUID.randomUUID());
  }

  public static MessageEnvelope event(String messageType, Object payload, UUID aggregateId, int aggregateVersion, UUID messageId) {
    JsonNode payloadNode = MAPPER.valueToTree(payload);
    return new MessageEnvelope(
        messageId, messageType, 1, MessageKind.EVENT, "producer", Instant.now(), UUID.randomUUID(), null, null, null, aggregateId, aggregateVersion, null,
        payloadNode);
  }
}
