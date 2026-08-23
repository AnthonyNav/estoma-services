package com.estoma.washexecution.infrastructure.messaging;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    return mapper.writeValueAsBytes(envelope);
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
}
