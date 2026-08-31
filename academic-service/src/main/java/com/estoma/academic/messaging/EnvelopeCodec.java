package com.estoma.academic.messaging;

import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.contract.Actor;
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
      root.set("payload", envelope.payload());
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
