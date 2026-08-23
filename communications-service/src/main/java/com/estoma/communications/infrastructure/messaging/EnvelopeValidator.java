package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class EnvelopeValidator {

  private static final int VERSION = 1;
  private static final Set<String> COMMANDS = Arrays.stream(CommunicationsCommand.values())
      .map(CommunicationsCommand::wireName).collect(Collectors.toSet());
  private static final Map<String, String> EVENT_OWNERS = Map.ofEntries(
      Map.entry(ConsumedEventTypes.PERSON_CREATED, "users"),
      Map.entry(ConsumedEventTypes.PERSON_UPDATED, "users"),
      Map.entry(ConsumedEventTypes.PERSON_ACTIVATED, "users"),
      Map.entry(ConsumedEventTypes.PERSON_DEACTIVATED, "users"),
      Map.entry(ConsumedEventTypes.ACCOUNT_CREATED, "authentication"),
      Map.entry(ConsumedEventTypes.ACCOUNT_ACTIVATED, "authentication"),
      Map.entry(ConsumedEventTypes.ACCOUNT_DEACTIVATED, "authentication"),
      Map.entry(ConsumedEventTypes.APPOINTMENT_CANCELLED, "appointments"));

  public void validateCommand(MessageEnvelope envelope) {
    common(envelope);
    if (envelope.messageKind() != MessageKind.COMMAND || !COMMANDS.contains(envelope.messageType())) {
      throw new IllegalArgumentException("invalid Communications command envelope");
    }
    if (envelope.operationId() == null || envelope.idempotencyKey() == null || envelope.actor() == null) {
      throw new IllegalArgumentException("command requires operationId, idempotencyKey and actor");
    }
  }

  public void validateEvent(MessageEnvelope envelope) {
    common(envelope);
    if (envelope.messageKind() != MessageKind.EVENT) {
      throw new IllegalArgumentException("expected EVENT envelope");
    }
    String owner = EVENT_OWNERS.get(envelope.messageType());
    if (owner == null || !owner.equals(envelope.producer())) {
      throw new IllegalArgumentException("event producer does not own " + envelope.messageType());
    }
    if (envelope.aggregateId() == null || envelope.aggregateVersion() == null || envelope.aggregateVersion() < 1
        || envelope.payload() == null || !envelope.payload().isObject()) {
      throw new IllegalArgumentException("event requires aggregate identity/version and object payload");
    }
    UUID payloadAggregate = switch (envelope.messageType()) {
      case ConsumedEventTypes.PERSON_CREATED, ConsumedEventTypes.PERSON_UPDATED,
          ConsumedEventTypes.PERSON_ACTIVATED, ConsumedEventTypes.PERSON_DEACTIVATED -> uuid(envelope, "personId");
      case ConsumedEventTypes.ACCOUNT_CREATED, ConsumedEventTypes.ACCOUNT_ACTIVATED,
          ConsumedEventTypes.ACCOUNT_DEACTIVATED -> uuid(envelope, "accountId");
      case ConsumedEventTypes.APPOINTMENT_CANCELLED -> uuid(envelope, "appointmentId");
      default -> throw new IllegalArgumentException("unsupported external event");
    };
    if (!envelope.aggregateId().equals(payloadAggregate)) {
      throw new IllegalArgumentException("event aggregateId does not match owner payload");
    }
  }

  private static void common(MessageEnvelope envelope) {
    if (envelope == null || envelope.messageId() == null || envelope.correlationId() == null
        || envelope.occurredAt() == null || envelope.messageType() == null || envelope.messageType().isBlank()
        || envelope.producer() == null || envelope.producer().isBlank() || envelope.messageKind() == null
        || envelope.messageVersion() != VERSION) {
      throw new IllegalArgumentException("invalid canonical envelope");
    }
  }

  private static UUID uuid(MessageEnvelope envelope, String field) {
    try {
      return UUID.fromString(envelope.payload().path(field).asString());
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(field + " must be UUID", exception);
    }
  }
}
