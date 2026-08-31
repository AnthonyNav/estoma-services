package com.estoma.academic.messaging;

import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.contract.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class EnvelopeCodec {
  private final ObjectMapper mapper;
  public EnvelopeCodec(ObjectMapper mapper) { this.mapper = mapper; }
  public MessageEnvelope decode(byte[] body) { return mapper.readValue(body, MessageEnvelope.class); }
  /** Serializes the strict V1 wire form expected by remote consumers, omitting inapplicable fields. */
  public byte[] encode(MessageEnvelope envelope) {
    ObjectNode root = mapper.createObjectNode();
    root.put("messageId", envelope.messageId().toString());
    root.put("messageType", envelope.messageType());
    root.put("messageVersion", envelope.messageVersion());
    root.put("messageKind", envelope.messageKind());
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
      root.set("payload", withoutNullObjectFields(envelope.payload()));
    }
    return mapper.writeValueAsBytes(root);
  }
  public String json(Object value) { return mapper.writeValueAsString(value); }
  public JsonNode node(String value) { return mapper.readTree(value); }
  public Actor actor(JsonNode value) { return value == null || value.isNull() ? null : mapper.treeToValue(value, Actor.class); }

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

  private JsonNode withoutNullObjectFields(JsonNode value) {
    if (!value.isObject()) {
      throw new IllegalArgumentException("payload must be an object");
    }
    ObjectNode copy = (ObjectNode) value.deepCopy();
    removeNullObjectFields(copy);
    return copy;
  }

  private static void removeNullObjectFields(ObjectNode object) {
    List<String> nullFields = new ArrayList<>();
    for (Map.Entry<String, JsonNode> field : object.properties()) {
      if (field.getValue().isNull()) {
        nullFields.add(field.getKey());
      } else if (field.getValue().isObject()) {
        removeNullObjectFields((ObjectNode) field.getValue());
      } else if (field.getValue().isArray()) {
        removeNullArrayItems(field.getValue());
      }
    }
    object.remove(nullFields);
  }

  private static void removeNullArrayItems(JsonNode array) {
    for (JsonNode item : array) {
      if (item.isNull()) {
        throw new IllegalArgumentException("payload arrays must not contain null values");
      }
      if (item.isObject()) {
        removeNullObjectFields((ObjectNode) item);
      } else if (item.isArray()) {
        removeNullArrayItems(item);
      }
    }
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
