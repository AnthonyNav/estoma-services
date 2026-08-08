package com.estoma.authentication.infrastructure.messaging;

import com.estoma.authentication.contracts.MessageEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Encodes/decodes the platform message envelope. Uses the application's
 * shared {@link ObjectMapper} bean (Jackson 3, the default JSON stack as of
 * Spring Boot 4.1) rather than constructing a private one.
 *
 * <p>Must tolerate unknown fields on deserialization for forward
 * compatibility (messaging-architecture.md:499) -- enforced by the shared
 * mapper's default configuration, not by this class.
 */
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

  /** Converts the envelope's raw {@code payload} into a specific payload type. */
  public <T> T convertPayload(MessageEnvelope envelope, Class<T> payloadType) {
    return mapper.treeToValue(envelope.payload(), payloadType);
  }

  /** Converts an arbitrary value into a {@link JsonNode}, for building outbound payloads. */
  public JsonNode toJsonNode(Object value) {
    return mapper.valueToTree(value);
  }

  public String writeValueAsString(Object value) {
    return mapper.writeValueAsString(value);
  }
}
