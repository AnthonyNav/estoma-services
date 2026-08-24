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

    if (envelope.messageId() == null) throw new InvalidMessageException("messageId is required");
    if (envelope.messageType() == null || envelope.messageType().isBlank())
      throw new InvalidMessageException("messageType is required");
    if (envelope.messageVersion() != 1) throw new InvalidMessageException("unsupported envelope version");
    if (envelope.messageKind() != expectedKind) throw new InvalidMessageException("unexpected message kind");
    if (!allowedTypes.contains(envelope.messageType())) throw new InvalidMessageException("unsupported message type");
    if (envelope.producer() == null || envelope.producer().isBlank())
      throw new InvalidMessageException("producer is required");
    if (envelope.occurredAt() == null) throw new InvalidMessageException("occurredAt is required");
    if (envelope.correlationId() == null) throw new InvalidMessageException("correlationId is required");
    if (envelope.aggregateVersion() != null && envelope.aggregateVersion() <= 0)
      throw new InvalidMessageException("aggregateVersion must be positive when present");
    validateActor(envelope);
    if (envelope.payload() == null || !envelope.payload().isObject())
      throw new InvalidMessageException("payload must be an object");
    return envelope;
  }

  private static void validateActor(MessageEnvelope envelope) {
    var actor = envelope.actor();
    if (actor == null || actor.type() == null) throw new InvalidMessageException("actor is required");
    switch (actor.type()) {
      case "ACCOUNT" -> {
        if (actor.accountId() == null) throw new InvalidMessageException("ACCOUNT actor requires accountId");
        if (actor.service() != null && !actor.service().isBlank())
          throw new InvalidMessageException("ACCOUNT actor must not carry service");
      }
      case "SYSTEM" -> {
        if (actor.accountId() != null) throw new InvalidMessageException("SYSTEM actor must not carry accountId");
        if (actor.service() == null || actor.service().isBlank())
          throw new InvalidMessageException("SYSTEM actor requires service");
      }
      default -> throw new InvalidMessageException("actor.type must be ACCOUNT or SYSTEM");
    }
  }
}
