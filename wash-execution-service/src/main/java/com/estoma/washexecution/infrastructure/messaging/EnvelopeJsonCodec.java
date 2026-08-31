package com.estoma.washexecution.infrastructure.messaging;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
    root.set("actor", actorNode(envelope.actor()));
    root.set("payload", withoutNullObjectFields(envelope.payload()));
    return mapper.writeValueAsBytes(root);
  }

  public <T> T convertPayload(MessageEnvelope envelope, Class<T> type) {
    return mapper.treeToValue(envelope.payload(), type);
  }

  public <T> T convertNode(JsonNode node, Class<T> type) {
    return mapper.treeToValue(node, type);
  }

  public JsonNode toNode(Object value) {
    return mapper.valueToTree(value);
  }

  public JsonNode readNode(String value) {
    return value == null ? null : mapper.readValue(value, JsonNode.class);
  }

  public Actor readActor(String value) {
    return value == null ? null : mapper.readValue(value, Actor.class);
  }

  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  private ObjectNode actorNode(Actor actor) {
    if (actor == null) {
      throw new IllegalArgumentException("actor is required");
    }
    ObjectNode node = mapper.createObjectNode();
    node.put("type", actor.type());
    if ("SYSTEM".equals(actor.type())) {
      if (actor.service() == null || actor.service().isBlank()) {
        throw new IllegalArgumentException("system actor service is required");
      }
      node.put("service", actor.service());
      return node;
    }
    if ("ACCOUNT".equals(actor.type())) {
      if (actor.accountId() == null) {
        throw new IllegalArgumentException("account actor accountId is required");
      }
      node.put("accountId", actor.accountId().toString());
      return node;
    }
    throw new IllegalArgumentException("unsupported actor type: " + actor.type());
  }

  private JsonNode withoutNullObjectFields(JsonNode value) {
    if (value == null || value.isNull() || !value.isObject()) {
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

  private static void putUuid(ObjectNode node, String field, UUID value) {
    if (value != null) {
      node.put(field, value.toString());
    }
  }

  private static void putText(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }
}
