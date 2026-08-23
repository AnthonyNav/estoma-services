package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import java.util.Set;
import tools.jackson.databind.JsonNode;

@org.springframework.stereotype.Component
public final class EnvelopeValidator {
  private final EnvelopeJsonCodec codec;
  public EnvelopeValidator(EnvelopeJsonCodec codec) { this.codec = codec; }

  public MessageEnvelope validate(JsonNode root, MessageKind expectedKind, Set<String> allowedTypes) {
    if (root == null || !root.isObject()) throw new InvalidMessageException("envelope must be an object");
    MessageEnvelope envelope;
    try { envelope = codec.decode(root); }
    catch (RuntimeException exception) { throw new InvalidMessageException("invalid envelope", exception); }
    if (envelope.messageVersion() != 1) throw new InvalidMessageException("unsupported envelope version");
    if (envelope.messageKind() != expectedKind) throw new InvalidMessageException("unexpected message kind");
    if (!allowedTypes.contains(envelope.messageType())) throw new InvalidMessageException("unsupported message type");
    if (envelope.payload() == null || !envelope.payload().isObject()) throw new InvalidMessageException("payload must be an object");
    return envelope;
  }
}
