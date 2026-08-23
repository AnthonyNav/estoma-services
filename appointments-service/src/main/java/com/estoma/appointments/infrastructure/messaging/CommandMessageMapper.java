package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.in.CancelAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.CancelAppointmentUseCase;
import com.estoma.appointments.application.port.in.CancelExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.in.CreateAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.GrantExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.in.MarkAppointmentAsMissedUseCase;
import com.estoma.appointments.application.port.in.RecalculateAppointmentCapacityForTimeSlotUseCase;
import com.estoma.appointments.application.port.in.ScheduleAppointmentUseCase;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class CommandMessageMapper {
  public MappedCommand map(MessageEnvelope envelope) {
    if (envelope.correlationId() == null) {
      throw new InvalidMessageException("correlationId is required for commands");
    }
    CommandMetadata metadata = new CommandMetadata(envelope.messageId(),
        envelope.idempotencyKey() == null ? envelope.messageId().toString() : envelope.idempotencyKey(),
        envelope.correlationId(), envelope.causationId(), actor(envelope.actor()), envelope.occurredAt(),
        envelope.operationId());
    JsonNode p = envelope.payload();
    return switch (envelope.messageType()) {
      case "ScheduleAppointment" -> new MappedCommand(metadata, new ScheduleAppointmentUseCase.Command(metadata,
          uuid(p, "studentAccountId"), uuid(p, "appointmentTimeSlotId"), uuid(p, "courseSectionId"),
          enumValue(AppointmentType.class, text(p, "appointmentType")), intValue(p, "instrumentCount"),
          enumValue(PieceType.class, text(p, "pieceType")), optionalUuid(p, "exceptionalAuthorizationId"),
          envelope.occurredAt()));
      case "CancelAppointment" -> new MappedCommand(metadata, new CancelAppointmentUseCase.Command(metadata,
          uuid(p, "appointmentId"), longValue(p, "expectedVersion"), optionalText(p, "reason"),
          envelope.occurredAt()));
      case "GrantExceptionalAppointmentAuthorization" -> new MappedCommand(metadata,
          new GrantExceptionalAppointmentAuthorizationUseCase.Command(metadata, uuid(p, "authorizationId"),
              uuid(p, "studentAccountId"), LocalDate.parse(text(p, "serviceDate")), text(p, "reason"),
              optionalInstant(p, "expiresAt"), envelope.occurredAt()));
      case "CancelExceptionalAppointmentAuthorization" -> new MappedCommand(metadata,
          new CancelExceptionalAppointmentAuthorizationUseCase.Command(metadata, uuid(p, "authorizationId"),
              text(p, "reason"), envelope.occurredAt()));
      case "CreateAppointmentTimeSlot" -> new MappedCommand(metadata,
          new CreateAppointmentTimeSlotUseCase.Command(metadata, uuid(p, "slotId"), uuid(p, "sourceScheduleId"),
              LocalDate.parse(text(p, "serviceDate")), Instant.parse(text(p, "startsAt")),
              Instant.parse(text(p, "endsAt")), ZoneId.of(text(p, "timezone")),
              Instant.parse(text(p, "bookingDeadlineAt")), Instant.parse(text(p, "cancellationDeadlineAt")),
              Instant.parse(text(p, "arrivalToleranceUntil")), envelope.occurredAt()));
      case "CancelAppointmentTimeSlot" -> new MappedCommand(metadata,
          new CancelAppointmentTimeSlotUseCase.Command(metadata, uuid(p, "slotId"),
              longValue(p, "expectedVersion"), envelope.occurredAt()));
      case "MarkAppointmentAsMissed" -> new MappedCommand(metadata,
          new MarkAppointmentAsMissedUseCase.Command(metadata, uuid(p, "appointmentId"),
              longValue(p, "expectedVersion"), envelope.occurredAt()));
      case "RecalculateAppointmentCapacityForTimeSlot" -> new MappedCommand(metadata,
          new RecalculateAppointmentCapacityForTimeSlotUseCase.Command(metadata, uuid(p, "sourceEventId"),
              uuid(p, "slotId"), longValueAllowZero(p, "expectedProjectionVersion"), envelope.occurredAt()));
      default -> throw new InvalidMessageException("unsupported command " + envelope.messageType());
    };
  }

  private static ActorContext actor(WireActor actor) {
    if (actor == null || actor.type() == null) throw new InvalidMessageException("actor is required");
    return switch (actor.type()) {
      case "ACCOUNT" -> ActorContext.account(required(actor.accountId(), "ACCOUNT actor requires accountId"));
      case "SYSTEM" -> ActorContext.system(requiredText(actor.service(), "SYSTEM actor requires service"));
      default -> throw new InvalidMessageException("actor.type must be ACCOUNT or SYSTEM");
    };
  }

  private static UUID required(UUID value, String message) {
    if (value == null) throw new InvalidMessageException(message);
    return value;
  }
  private static String requiredText(String value, String message) {
    if (value == null || value.isBlank()) throw new InvalidMessageException(message);
    return value;
  }
  private static UUID uuid(JsonNode node, String field) {
    UUID value = optionalUuid(node, field);
    if (value == null) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static UUID optionalUuid(JsonNode node, String field) {
    String value = optionalText(node, field);
    try { return value == null ? null : UUID.fromString(value); }
    catch (IllegalArgumentException exception) { throw new InvalidMessageException(field + " must be UUID", exception); }
  }
  private static Instant optionalInstant(JsonNode node, String field) {
    String value = optionalText(node, field);
    try { return value == null ? null : Instant.parse(value); }
    catch (RuntimeException exception) { throw new InvalidMessageException(field + " must be ISO-8601 instant", exception); }
  }
  private static String text(JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static String optionalText(JsonNode node, String field) {
    return !node.has(field) || node.path(field).isNull() || !node.path(field).isTextual()
        ? null : node.path(field).asText();
  }
  private static int intValue(JsonNode node, String field) {
    if (!node.has(field) || !node.path(field).canConvertToInt())
      throw new InvalidMessageException(field + " must be an integer");
    return node.path(field).asInt();
  }
  private static long longValue(JsonNode node, String field) {
    long value = longValueAllowZero(node, field);
    if (value <= 0) throw new InvalidMessageException(field + " must be positive");
    return value;
  }
  private static long longValueAllowZero(JsonNode node, String field) {
    if (!node.has(field) || !node.path(field).isIntegralNumber())
      throw new InvalidMessageException(field + " must be an integer");
    long value = node.path(field).asLong();
    if (value < 0) throw new InvalidMessageException(field + " cannot be negative");
    return value;
  }
  private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
    try { return Enum.valueOf(type, value); }
    catch (IllegalArgumentException exception) {
      throw new InvalidMessageException("invalid " + type.getSimpleName(), exception);
    }
  }

  public record MappedCommand(CommandMetadata metadata, Object command) {}
}
