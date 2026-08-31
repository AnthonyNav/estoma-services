package com.estoma.washadministration.infrastructure.messaging;

import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public final class EnvelopeJsonCodec {

  private final ObjectMapper mapper;

  public EnvelopeJsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public MessageEnvelope decode(byte[] body) {
    return mapper.readValue(body, MessageEnvelope.class);
  }

  public byte[] encode(MessageEnvelope envelope) {
    ObjectNode root = mapper.createObjectNode();
    root.put("messageId", envelope.messageId().toString());
    root.put("messageType", envelope.messageType());
    root.put("messageVersion", envelope.messageVersion());
    root.put("messageKind", envelope.messageKind().name());
    root.put("producer", envelope.producer());
    root.put("occurredAt", envelope.occurredAt().toString());
    root.put("correlationId", envelope.correlationId().toString());
    putUuid(root, "causationId", envelope.causationId());
    putUuid(root, "operationId", envelope.operationId());
    putText(root, "idempotencyKey", envelope.idempotencyKey());
    putUuid(root, "aggregateId", envelope.aggregateId());
    if (envelope.aggregateVersion() != null) {
      root.put("aggregateVersion", envelope.aggregateVersion());
    }
    if (envelope.actor() != null) {
      root.set("actor", actorNode(envelope.actor()));
    }
    if (envelope.payload() != null) {
      root.set("payload", envelope.payload());
    }
    return mapper.writeValueAsBytes(root);
  }

  public <T> T convertPayload(MessageEnvelope envelope, Class<T> type) {
    return mapper.treeToValue(envelope.payload(), type);
  }

  public <T> T convertNode(JsonNode node, Class<T> type) {
    return mapper.treeToValue(node, type);
  }

  public JsonNode toJsonNode(Object value) {
    return mapper.valueToTree(value);
  }

  public JsonNode readTree(String value) {
    return value == null ? null : mapper.readValue(value, JsonNode.class);
  }

  public Actor readActor(String value) {
    return mapper.readValue(value, Actor.class);
  }

  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  private ObjectNode actorNode(Actor actor) {
    ObjectNode node = mapper.createObjectNode();
    node.put("type", actor.type());
    if ("SYSTEM".equals(actor.type())) {
      if (actor.service() == null || actor.service().isBlank()) {
        throw new IllegalArgumentException("SYSTEM actor requires service");
      }
      node.put("service", actor.service());
    } else if ("ACCOUNT".equals(actor.type())) {
      if (actor.accountId() == null) {
        throw new IllegalArgumentException("ACCOUNT actor requires accountId");
      }
      node.put("accountId", actor.accountId().toString());
    } else {
      throw new IllegalArgumentException("unsupported actor type: " + actor.type());
    }
    return node;
  }

  private static void putUuid(ObjectNode root, String field, UUID value) {
    if (value != null) {
      root.put(field, value.toString());
    }
  }

  private static void putText(ObjectNode root, String field, String value) {
    if (value != null) {
      root.put(field, value);
    }
  }
}
