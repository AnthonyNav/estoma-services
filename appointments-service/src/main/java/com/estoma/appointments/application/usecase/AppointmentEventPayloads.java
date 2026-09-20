package com.estoma.appointments.application.usecase;

import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Canonical public Event payloads. Envelope owns occurredAt/aggregateVersion; payload keeps V1 fields. */
final class AppointmentEventPayloads {
  private AppointmentEventPayloads() {}

  static String timeSlotCreated(AppointmentTimeSlot slot) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("appointmentTimeSlotId", slot.appointmentTimeSlotId());
    fields.put("calendarId", slot.calendarId()); // required by Wash Execution calendar-context resolution
    fields.put("sourceScheduleId", slot.sourceScheduleId());
    fields.put("serviceDate", slot.serviceDate());
    fields.put("startsAt", slot.startsAt());
    fields.put("endsAt", slot.endsAt());
    fields.put("timezone", slot.timezone().getId());
    fields.put("bookingDeadlineAt", slot.bookingDeadlineAt());
    fields.put("cancellationDeadlineAt", slot.cancellationDeadlineAt());
    fields.put("arrivalToleranceUntil", slot.arrivalToleranceUntil());
    fields.put("status", slot.status());
    fields.put("version", slot.version());
    return object(fields);
  }

  static String timeSlotCancelled(AppointmentTimeSlot slot, String reason) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("appointmentTimeSlotId", slot.appointmentTimeSlotId());
    fields.put("calendarId", slot.calendarId());
    fields.put("serviceDate", slot.serviceDate());
    fields.put("startsAt", slot.startsAt());
    fields.put("endsAt", slot.endsAt());
    fields.put("reason", reason);
    fields.put("version", slot.version());
    return object(fields);
  }

  static String scheduled(Appointment appointment) {
    return scheduled(appointment, null);
  }

  static String scheduled(Appointment appointment, String qrRepresentation) {
    Map<String, Object> fields = baseAppointment(appointment);
    fields.put("courseSectionId", appointment.courseSectionId());
    fields.put("instrumentCount", appointment.instrumentCount());
    fields.put("pieceType", appointment.pieceType());
    if (appointment.exceptionalAuthorizationId() != null) {
      fields.put("exceptionalAuthorizationId", appointment.exceptionalAuthorizationId());
    }
    fields.put("createdAt", appointment.createdAt());
    fields.put("version", appointment.version());
    if (qrRepresentation != null) {
      fields.put("qrRepresentation", qrRepresentation);
    }
    return object(fields);
  }

  static String cancelled(Appointment appointment) {
    return cancelled(appointment, null, null);
  }

  static String cancelled(Appointment appointment, UUID washExecutionId, UUID sourceEventId) {
    Map<String, Object> fields = baseAppointment(appointment);
    fields.put("cancellationType", appointment.cancellationType());
    if (appointment.cancellationSubreason() != null) {
      fields.put("cancellationSubreason", appointment.cancellationSubreason());
    }
    if (appointment.cancellationReason() != null) {
      fields.put("cancellationReason", appointment.cancellationReason());
    }
    if (appointment.cancelledByAccountId() != null) {
      fields.put("cancelledByAccountId", appointment.cancelledByAccountId());
    }
    fields.put("cancelledAt", appointment.cancelledAt());
    if (washExecutionId != null) fields.put("washExecutionId", washExecutionId);
    if (sourceEventId != null) fields.put("sourceEventId", sourceEventId);
    fields.put("version", appointment.version());
    return object(fields);
  }

  static String missed(Appointment appointment) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("appointmentId", appointment.appointmentId());
    fields.put("appointmentTimeSlotId", appointment.appointmentTimeSlotId());
    fields.put("studentAccountId", appointment.studentAccountId());
    fields.put("missedAt", appointment.missedAt());
    fields.put("version", appointment.version());
    return object(fields);
  }

  static String entryRejected(Appointment appointment) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("appointmentId", appointment.appointmentId());
    fields.put("appointmentTimeSlotId", appointment.appointmentTimeSlotId());
    fields.put("studentAccountId", appointment.studentAccountId());
    fields.put("rejectionReason", appointment.rejectionReason());
    if (appointment.rejectedByAccountId() != null) {
      fields.put("rejectedByAccountId", appointment.rejectedByAccountId());
    }
    fields.put("rejectedAt", appointment.rejectedAt());
    fields.put("version", appointment.version());
    return object(fields);
  }

  static String started(Appointment appointment) {
    Map<String, Object> fields = baseAppointment(appointment);
    fields.put("startedAt", appointment.startedAt());
    fields.put("version", appointment.version());
    return object(fields);
  }

  static String completed(Appointment appointment) {
    Map<String, Object> fields = baseAppointment(appointment);
    fields.put("courseSectionId", appointment.courseSectionId());
    if (appointment.completedByAccountId() != null) {
      fields.put("completedByAccountId", appointment.completedByAccountId());
    }
    fields.put("completedAt", appointment.completedAt());
    fields.put("version", appointment.version());
    return object(fields);
  }

  static String authorizationGranted(ExceptionalAppointmentAuthorization authorization) {
    return authorizationGranted(authorization, java.util.List.of());
  }

  static String authorizationGranted(ExceptionalAppointmentAuthorization authorization,
      java.util.List<ExceptionalAppointmentAuthorization> superseded) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("authorizationId", authorization.exceptionalAuthorizationId());
    fields.put("studentAccountId", authorization.studentAccountId());
    fields.put("authorizationDate", authorization.authorizationDate());
    fields.put("authorizedByAccountId", authorization.authorizedByAccountId());
    fields.put("reason", authorization.reason());
    fields.put("grantedAt", authorization.grantedAt());
    fields.put("policyContextKnown", true);
    fields.put("expiresAt", authorization.expiresAt());
    fields.put("supersededAuthorizationIds", superseded.stream()
        .map(ExceptionalAppointmentAuthorization::exceptionalAuthorizationId).toList());
    return object(fields);
  }

  static String authorizationCancelled(ExceptionalAppointmentAuthorization authorization) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("authorizationId", authorization.exceptionalAuthorizationId());
    fields.put("studentAccountId", authorization.studentAccountId());
    fields.put("authorizationDate", authorization.authorizationDate());
    fields.put("cancellationReason", authorization.cancellationReason());
    fields.put("cancelledByAccountId", authorization.cancelledByAccountId());
    fields.put("cancelledAt", authorization.cancelledAt());
    return object(fields);
  }

  static String capacityRecalculated(UUID slotId, int normal, int journey, int immuno,
      long projectionVersion) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("appointmentTimeSlotId", slotId);
    fields.put("capacityNormal", normal);
    fields.put("capacityJourney", journey);
    fields.put("capacityImmunocompromised", immuno);
    fields.put("projectionVersion", projectionVersion);
    return object(fields);
  }

  static String capacityReduced(UUID slotId,
      int previousNormal, int currentNormal,
      int previousJourney, int currentJourney,
      int previousImmuno, int currentImmuno,
      int affectedAppointmentsCount, long projectionVersion) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("appointmentTimeSlotId", slotId);
    fields.put("previousCapacityNormal", previousNormal);
    fields.put("currentCapacityNormal", currentNormal);
    fields.put("previousCapacityJourney", previousJourney);
    fields.put("currentCapacityJourney", currentJourney);
    fields.put("previousCapacityImmunocompromised", previousImmuno);
    fields.put("currentCapacityImmunocompromised", currentImmuno);
    fields.put("affectedAppointmentsCount", affectedAppointmentsCount);
    fields.put("projectionVersion", projectionVersion);
    return object(fields);
  }

  private static Map<String, Object> baseAppointment(Appointment appointment) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("appointmentId", appointment.appointmentId());
    fields.put("appointmentTimeSlotId", appointment.appointmentTimeSlotId());
    fields.put("studentAccountId", appointment.studentAccountId());
    fields.put("appointmentType", appointment.appointmentType());
    return fields;
  }

  private static String object(Map<String, Object> fields) {
    StringBuilder result = new StringBuilder("{");
    boolean first = true;
    for (var entry : fields.entrySet()) {
      if (entry.getValue() == null) continue;
      if (!first) result.append(',');
      first = false;
      result.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
    }
    return result.append('}').toString();
  }

  private static String value(Object value) {
    if (value instanceof java.util.List<?> list) return list.stream()
        .map(AppointmentEventPayloads::value).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
    return quote(String.valueOf(value));
  }

  private static String quote(String value) {
    StringBuilder result = new StringBuilder("\"");
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> result.append("\\\\");
        case '"' -> result.append("\\\"");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        default -> result.append(character);
      }
    }
    return result.append('"').toString();
  }
}
