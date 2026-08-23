package com.estoma.authorization.infrastructure.messaging;

import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Encodes V1 envelopes while omitting fields that are not applicable. */
@Component
public final class EnvelopeJsonCodec {

  private final ObjectMapper mapper;

  public EnvelopeJsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public JsonNode readTree(byte[] body) {
    return mapper.readTree(body);
  }

  public JsonNode readTree(String body) {
    return mapper.readTree(body);
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

  public MessageEnvelope decode(JsonNode root) {
    return new MessageEnvelope(
        requiredUuid(root, "messageId"),
        requiredText(root, "messageType"),
        requiredInt(root, "messageVersion"),
        MessageKind.valueOf(requiredText(root, "messageKind")),
        requiredText(root, "producer"),
        Instant.parse(requiredText(root, "occurredAt")),
        requiredUuid(root, "correlationId"),
        optionalUuid(root, "causationId"),
        optionalUuid(root, "operationId"),
        optionalText(root, "idempotencyKey"),
        optionalUuid(root, "aggregateId"),
        optionalInt(root, "aggregateVersion"),
        decodeActor(root.path("actor")),
        root.path("payload"));
  }

  public String write(JsonNode value) {
    return mapper.writeValueAsString(value);
  }

  private ObjectNode actorNode(WireActor actor) {
    if (actor == null) {
      throw new IllegalArgumentException("actor is required");
    }
    ObjectNode node = mapper.createObjectNode();
    node.put("type", actor.type());
    if (actor.accountId() != null) {
      node.put("accountId", actor.accountId().toString());
    }
    if (actor.service() != null) {
      node.put("service", actor.service());
    }
    return node;
  }

  private WireActor decodeActor(JsonNode node) {
    return new WireActor(
        requiredText(node, "type"), optionalUuid(node, "accountId"), optionalText(node, "service"));
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

  private static UUID requiredUuid(JsonNode node, String field) {
    String value = requiredText(node, field);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a UUID", exception);
    }
  }

  private static UUID optionalUuid(JsonNode node, String field) {
    String value = optionalText(node, field);
    return value == null ? null : UUID.fromString(value);
  }

  private static int requiredInt(JsonNode node, String field) {
    if (!node.has(field) || !node.path(field).isInt()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    return node.path(field).asInt();
  }

  private static Integer optionalInt(JsonNode node, String field) {
    if (!node.has(field)) {
      return null;
    }
    return requiredInt(node, field);
  }

  private static String requiredText(JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field) {
    if (!node.has(field) || node.path(field).isNull() || !node.path(field).isTextual()) {
      return null;
    }
    return node.path(field).asText();
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
