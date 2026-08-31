package com.estoma.registros.messaging;

import com.estoma.registros.contract.MessageEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class EnvelopeCodec {
  private final ObjectMapper mapper;

  public EnvelopeCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public MessageEnvelope decode(byte[] body) {
    return mapper.readValue(body, MessageEnvelope.class);
  }

  public byte[] encode(MessageEnvelope envelope) {
    return mapper.writeValueAsBytes(envelope);
  }

  public JsonNode valueToTree(Object value) {
    return mapper.valueToTree(value);
  }

  public <T> T treeToValue(JsonNode node, Class<T> type) {
    return node == null || node.isNull() ? null : mapper.treeToValue(node, type);
  }
}
