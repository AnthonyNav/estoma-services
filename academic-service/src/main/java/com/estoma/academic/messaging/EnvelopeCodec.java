package com.estoma.academic.messaging;

import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.contract.Actor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class EnvelopeCodec {
  private final ObjectMapper mapper;
  public EnvelopeCodec(ObjectMapper mapper) { this.mapper = mapper; }
  public MessageEnvelope decode(byte[] body) { return mapper.readValue(body, MessageEnvelope.class); }
  public byte[] encode(MessageEnvelope envelope) { return mapper.writeValueAsBytes(envelope); }
  public String json(Object value) { return mapper.writeValueAsString(value); }
  public JsonNode node(String value) { return mapper.readTree(value); }
  public Actor actor(JsonNode value) { return value == null || value.isNull() ? null : mapper.treeToValue(value, Actor.class); }
}
