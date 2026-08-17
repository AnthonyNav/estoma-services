package com.estoma.washadministration.infrastructure.messaging;

import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
    return mapper.writeValueAsBytes(envelope);
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
}
