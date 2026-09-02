package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.ActorType;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
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
      Map.entry(ConsumedEventTypes.PASSWORD_RECOVERY_REQUESTED, "authentication"),
      Map.entry(ConsumedEventTypes.APPOINTMENT_CANCELLED, "appointments"),
      Map.entry(ConsumedEventTypes.ACADEMIC_PERIOD_ACTIVATED, "academic"),
      Map.entry(ConsumedEventTypes.ACADEMIC_PERIOD_CLOSED, "academic"));

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
    if (envelope.messageKind() != MessageKind.EVENT || envelope.payload() == null || !envelope.payload().isObject()) {
      throw new IllegalArgumentException("expected canonical EVENT envelope");
    }
    String owner = EVENT_OWNERS.get(envelope.messageType());
    if (owner == null || !owner.equals(envelope.producer())) {
      throw new IllegalArgumentException("event producer does not own " + envelope.messageType());
    }

    if (ConsumedEventTypes.PASSWORD_RECOVERY_REQUESTED.equals(envelope.messageType())) {
      // Authentication intentionally emits this anti-enumeration fact without an aggregate identity.
      uuid(envelope, "recoveryId");
      uuid(envelope, "accountId");
      uuid(envelope, "personId");
      return;
    }

    if (envelope.aggregateId() == null || envelope.aggregateVersion() == null || envelope.aggregateVersion() < 1) {
      throw new IllegalArgumentException("event requires aggregate identity/version");
    }
    UUID payloadAggregate = switch (envelope.messageType()) {
      case ConsumedEventTypes.PERSON_CREATED, ConsumedEventTypes.PERSON_UPDATED,
          ConsumedEventTypes.PERSON_ACTIVATED, ConsumedEventTypes.PERSON_DEACTIVATED -> uuid(envelope, "personId");
      case ConsumedEventTypes.ACCOUNT_CREATED, ConsumedEventTypes.ACCOUNT_ACTIVATED,
          ConsumedEventTypes.ACCOUNT_DEACTIVATED -> uuid(envelope, "accountId");
      case ConsumedEventTypes.APPOINTMENT_CANCELLED -> uuid(envelope, "appointmentId");
      case ConsumedEventTypes.ACADEMIC_PERIOD_ACTIVATED, ConsumedEventTypes.ACADEMIC_PERIOD_CLOSED ->
          uuid(envelope, "academicPeriodId");
      default -> throw new IllegalArgumentException("unsupported external event");
    };
    if (!envelope.aggregateId().equals(payloadAggregate)) {
      throw new IllegalArgumentException("event aggregateId does not match owner payload");
    }
  }

  /** Validates the only Result this service consumes: its own Academic recovery response. */
  public void validateAcademicPeriodRecoveryResult(MessageEnvelope envelope) {
    common(envelope);
    if (envelope.messageKind() != MessageKind.RESULT
        || !"GetActiveAcademicPeriodSnapshotResult".equals(envelope.messageType())
        || !"academic".equals(envelope.producer())
        || envelope.causationId() == null
        || envelope.payload() == null
        || !envelope.payload().isObject()
        || envelope.actor() == null
        || envelope.actor().type() != ActorType.SYSTEM
        || !"communications".equals(envelope.actor().service())
        || envelope.actor().accountId() != null) {
      throw new IllegalArgumentException("invalid Academic period recovery Result envelope");
    }
    String status = envelope.payload().path("status").asString();
    if ("NOT_FOUND".equals(status)) {
      if (envelope.payload().hasNonNull("data") || envelope.aggregateId() != null || envelope.aggregateVersion() != null) {
        throw new IllegalArgumentException("NOT_FOUND Academic period Result must not carry aggregate data");
      }
      return;
    }
    if (!"FOUND".equals(status)) {
      throw new IllegalArgumentException("unsupported Academic period recovery Result status");
    }
    var data = envelope.payload().path("data");
    if (!data.isObject() || envelope.aggregateId() == null || envelope.aggregateVersion() == null
        || envelope.aggregateVersion() < 1 || !"ACTIVE".equals(data.path("status").asString())) {
      throw new IllegalArgumentException("invalid FOUND Academic period Result");
    }
    UUID periodId = uuid(data, "academicPeriodId");
    if (!envelope.aggregateId().equals(periodId) || envelope.aggregateVersion() != data.path("sourceVersion").asInt(-1)
        || data.path("code").asString().isBlank() || data.path("sourceVersion").asInt(0) < 1) {
      throw new IllegalArgumentException("Academic period Result aggregate/version mismatch");
    }
    try {
      LocalDate startDate = LocalDate.parse(data.path("startDate").asString());
      LocalDate endDate = LocalDate.parse(data.path("endDate").asString());
      Instant.parse(data.path("snapshotGeneratedAt").asString());
      if (endDate.isBefore(startDate)) throw new IllegalArgumentException("Academic period Result dates are inverted");
    } catch (RuntimeException invalidDate) {
      throw new IllegalArgumentException("invalid Academic period Result dates", invalidDate);
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

  private static UUID uuid(tools.jackson.databind.JsonNode payload, String field) {
    try {
      return UUID.fromString(payload.path(field).asString());
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(field + " must be UUID", exception);
    }
  }
}
