package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.in.CancelAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.CancelAppointmentUseCase;
import com.estoma.appointments.application.port.in.CancelExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.in.CreateAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.GrantExceptionalAppointmentAuthorizationUseCase;
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
    CommandMetadata metadata = new CommandMetadata(envelope.messageId(),
        envelope.idempotencyKey() == null ? envelope.messageId().toString() : envelope.idempotencyKey(),
        envelope.correlationId(), envelope.causationId(), actor(envelope.actor()), envelope.occurredAt());
    JsonNode p = envelope.payload();
    return switch (envelope.messageType()) {
      case "ScheduleAppointment" -> new MappedCommand(metadata, new ScheduleAppointmentUseCase.Command(metadata,
          uuid(p, "studentAccountId"), uuid(p, "appointmentTimeSlotId"), uuid(p, "courseSectionId"),
          enumValue(AppointmentType.class, text(p, "appointmentType")), intValue(p, "instrumentCount"),
          enumValue(PieceType.class, text(p, "pieceType")), optionalUuid(p, "exceptionalAuthorizationId"), envelope.occurredAt()));
      case "CancelAppointment" -> new MappedCommand(metadata, new CancelAppointmentUseCase.Command(metadata,
          uuid(p, "appointmentId"), "CLINIC".equals(text(p, "cancellationType")), optionalText(p, "reason"), envelope.occurredAt()));
      case "GrantExceptionalAppointmentAuthorization" -> new MappedCommand(metadata,
          new GrantExceptionalAppointmentAuthorizationUseCase.Command(metadata, optionalUuid(p, "authorizationId"),
              uuid(p, "studentAccountId"), LocalDate.parse(text(p, "serviceDate")), text(p, "reason"),
              optionalInstant(p, "expiresAt"), envelope.occurredAt()));
      case "CancelExceptionalAppointmentAuthorization" -> new MappedCommand(metadata,
          new CancelExceptionalAppointmentAuthorizationUseCase.Command(metadata, uuid(p, "authorizationId"),
              text(p, "reason"), envelope.occurredAt()));
      case "CreateAppointmentTimeSlot" -> new MappedCommand(metadata,
          new CreateAppointmentTimeSlotUseCase.Command(metadata, uuid(p, "slotId"), uuid(p, "sourceScheduleId"),
              LocalDate.parse(text(p, "serviceDate")), Instant.parse(text(p, "startsAt")), Instant.parse(text(p, "endsAt")),
              ZoneId.of(text(p, "timezone")), Instant.parse(text(p, "bookingDeadlineAt")),
              Instant.parse(text(p, "cancellationDeadlineAt")), Instant.parse(text(p, "arrivalToleranceUntil")), envelope.occurredAt()));
      case "CancelAppointmentTimeSlot" -> new MappedCommand(metadata,
          new CancelAppointmentTimeSlotUseCase.Command(metadata, uuid(p, "slotId"), envelope.occurredAt()));
      default -> throw new InvalidMessageException("unsupported command " + envelope.messageType());
    };
  }

  private static ActorContext actor(WireActor actor) {
    if (actor == null || actor.type() == null) throw new InvalidMessageException("actor is required");
    return new ActorContext(actor.accountId() == null ? new UUID(0, 0) : actor.accountId(), actor.type(),
        actor.service() == null ? "LAVADO_ULTRASONICO" : actor.service());
  }
  private static UUID uuid(JsonNode node, String field) { UUID value = optionalUuid(node, field); if (value == null) throw new InvalidMessageException(field + " is required"); return value; }
  private static UUID optionalUuid(JsonNode node, String field) { String value = optionalText(node, field); return value == null ? null : UUID.fromString(value); }
  private static Instant optionalInstant(JsonNode node, String field) { String value = optionalText(node, field); return value == null ? null : Instant.parse(value); }
  private static String text(JsonNode node, String field) { String value = optionalText(node, field); if (value == null || value.isBlank()) throw new InvalidMessageException(field + " is required"); return value; }
  private static String optionalText(JsonNode node, String field) { return !node.has(field) || node.path(field).isNull() || !node.path(field).isTextual() ? null : node.path(field).asText(); }
  private static int intValue(JsonNode node, String field) { if (!node.has(field) || !node.path(field).isInt()) throw new InvalidMessageException(field + " must be an integer"); return node.path(field).asInt(); }
  private static <T extends Enum<T>> T enumValue(Class<T> type, String value) { try { return Enum.valueOf(type, value); } catch (IllegalArgumentException exception) { throw new InvalidMessageException("invalid " + type.getSimpleName(), exception); } }

  public record MappedCommand(CommandMetadata metadata, Object command) {}
}
