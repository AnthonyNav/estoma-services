package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.contracts.MessageEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared Jackson codec for durable messaging contracts. */
@Component
public class EnvelopeJsonCodec {

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

  public <T> T convertPayload(MessageEnvelope envelope, Class<T> payloadType) {
    return mapper.treeToValue(envelope.payload(), payloadType);
  }

  public <T> T treeToValue(JsonNode value, Class<T> type) {
    return mapper.treeToValue(value, type);
  }

  public JsonNode toJsonNode(Object value) {
    return mapper.valueToTree(value);
  }

  public JsonNode node(String json) {
    return json == null ? null : mapper.readTree(json);
  }

  public String writeValueAsString(Object value) {
    return mapper.writeValueAsString(value);
  }
}
