package com.estoma.washexecution.infrastructure.messaging.recovery;

import com.estoma.washexecution.application.port.ProjectionRecoveryRepository;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CabinType;
import com.estoma.washexecution.infrastructure.messaging.inbound.ProjectionTrustCoordinator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class RecoveryResultProcessor {
  private final RecoveryJobStore jobs;
  private final ProjectionRecoveryRepository state;
  private final ProjectionTrustCoordinator trust;
  private final ProjectionRepositories.AppointmentRepository appointments;
  private final ProjectionRepositories.TimeSlotRepository slots;
  private final ProjectionRepositories.ResourceProjectionRepository resources;
  private final ProjectionRepositories.AdministrativeClosureRepository closures;
  private final ProjectionRepositories.AuthorizationProjectionRepository authorization;

  public RecoveryResultProcessor(
      RecoveryJobStore jobs,
      ProjectionRecoveryRepository state,
      ProjectionTrustCoordinator trust,
      ProjectionRepositories.AppointmentRepository appointments,
      ProjectionRepositories.TimeSlotRepository slots,
      ProjectionRepositories.ResourceProjectionRepository resources,
      ProjectionRepositories.AdministrativeClosureRepository closures,
      ProjectionRepositories.AuthorizationProjectionRepository authorization) {
    this.jobs = jobs;
    this.state = state;
    this.trust = trust;
    this.appointments = appointments;
    this.slots = slots;
    this.resources = resources;
    this.closures = closures;
    this.authorization = authorization;
  }

  public void process(MessageEnvelope envelope) {
    if (envelope.causationId() == null) return;
    var job = jobs.lockWaitingByQueryMessageId(envelope.causationId());
    if (job.isEmpty()) return;
    RecoveryJobStore.Job current = job.get();

    if (!matchesCurrentAttempt(current, envelope)) {
      fail(current, "RECOVERY_RESULT_FENCE_MISMATCH");
      return;
    }
    if (!current.projectionName().equals(envelope.producer())) {
      fail(current, "RECOVERY_RESULT_PRODUCER_MISMATCH");
      return;
    }

    String status = text(envelope.payload(), "status");
    if (status.equals("REJECTED") || status.equals("FAILED") || status.equals("EXPIRED")) {
      fail(current, "RECOVERY_RESULT_" + status);
      return;
    }

    try {
      switch (current.projectionName()) {
        case "appointments" -> applyAppointments(current, envelope.payload(), status);
        case "wash-administration" -> applyAdministration(current, envelope.payload(), status);
        case "authorization" -> applyAuthorization(current, envelope.payload(), status);
        default -> fail(current, "UNSUPPORTED_RECOVERY_PROJECTION");
      }
    } catch (RuntimeException ex) {
      fail(current, "RECOVERY_SNAPSHOT_INVALID:" + ex.getMessage());
    }
  }

  private boolean matchesCurrentAttempt(RecoveryJobStore.Job job, MessageEnvelope envelope) {
    String expectedType = job.currentQueryType() + "Result";
    return expectedType.equals(envelope.messageType())
        && job.currentQueryMessageId().equals(envelope.causationId())
        && job.currentOperationId().equals(envelope.operationId())
        && job.currentCorrelationId().equals(envelope.correlationId());
  }

  private void applyAppointments(RecoveryJobStore.Job job, JsonNode payload, String status) {
    if (!status.equals("SUCCEEDED")) {
      fail(job, "APPOINTMENTS_RECOVERY_STATUS_" + status);
      return;
    }
    JsonNode data = required(payload, "data");
    if (job.scope() == RecoveryJobStore.Scope.ENTITY) {
      JsonNode appointment = data.get("appointment");
      JsonNode timeSlot = data.get("timeSlot");
      if (appointment != null && !appointment.isNull() && timeSlot != null && !timeSlot.isNull()) {
        applyAppointmentContext(appointment, timeSlot);
      }
      jobs.complete(job.jobId(), Instant.now());
      trust.recoveryCompleted("appointments");
      return;
    }

    JsonNode contexts = required(data, "contexts");
    for (JsonNode context : contexts) {
      applyAppointmentContext(required(context, "appointment"), required(context, "timeSlot"));
    }
    String next = optionalText(data, "nextPageToken");
    if (next != null && !next.isBlank()) {
      jobs.advancePage(job.jobId(), next, Instant.now());
    } else {
      jobs.complete(job.jobId(), Instant.now());
      trust.recoveryCompleted("appointments");
    }
  }

  private void applyAppointmentContext(JsonNode a, JsonNode s) {
    UUID calendarId = optionalUuid(s, "calendarId");
    if (calendarId == null) {
      throw new IllegalArgumentException("APPOINTMENT_SLOT_CALENDAR_CONTEXT_MISSING");
    }
    appointments.upsert(new ProjectionRepositories.AppointmentContext(
        uuid(a, "appointmentId"), uuid(a, "studentAccountId"), uuid(a, "appointmentTimeSlotId"),
        uuid(a, "courseSectionId"), AppointmentType.valueOf(text(a, "appointmentType")),
        integer(a, "instrumentCount"), text(a, "pieceType"), text(a, "status"),
        optionalInstant(a, "entryAuthorizedAt"), longValue(a, "version")));
    slots.upsert(new ProjectionRepositories.TimeSlotContext(
        uuid(s, "appointmentTimeSlotId"), calendarId, LocalDate.parse(text(s, "serviceDate")),
        instant(s, "startsAt"), instant(s, "endsAt"), text(s, "timezone"),
        instant(s, "arrivalToleranceUntil"), text(s, "status"), longValue(s, "version")));
  }

  private void applyAdministration(RecoveryJobStore.Job job, JsonNode payload, String status) {
    if (!status.equals("SUCCEEDED")) {
      fail(job, "ADMIN_RECOVERY_STATUS_" + status);
      return;
    }
    JsonNode data = required(payload, "data");
    for (JsonNode c : required(data, "cabins")) {
      resources.upsertCabin(new ProjectionRepositories.CabinProjection(
          uuid(c, "cabinId"), text(c, "code"), optionalText(c, "name"),
          CabinType.valueOf(text(c, "cabinType")), text(c, "status"), longValue(c, "version")));
    }
    for (JsonNode t : required(data, "tanks")) {
      resources.upsertTank(new ProjectionRepositories.TankProjection(
          uuid(t, "tankId"), uuid(t, "cabinId"), text(t, "code"), optionalText(t, "name"),
          integer(t, "configuredCapacity"), text(t, "status"), longValue(t, "version")));
    }
    for (JsonNode p : required(data, "resourcePriorities")) {
      resources.upsertPriority(new ProjectionRepositories.ResourcePriorityProjection(
          uuid(p, "resourcePriorityId"), AppointmentType.valueOf(text(p, "appointmentType")),
          CabinType.valueOf(text(p, "cabinType")), text(p, "usageMode"), integer(p, "priorityOrder"),
          text(p, "status"), longValue(p, "version")));
    }
    for (JsonNode c : required(data, "administrativeClosures")) {
      closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
          uuid(c, "closureId"), uuid(c, "calendarId"), text(c, "scope"), optionalUuid(c, "cabinId"),
          optionalUuid(c, "tankId"), instant(c, "startsAt"), instant(c, "endsAt"), text(c, "status"),
          longValue(c, "version")));
    }
    jobs.complete(job.jobId(), Instant.now());
    trust.recoveryCompleted("wash-administration");
  }

  private void applyAuthorization(RecoveryJobStore.Job job, JsonNode payload, String status) {
    if (!status.equals("SUCCEEDED")) {
      fail(job, "AUTHORIZATION_RECOVERY_STATUS_" + status);
      return;
    }
    JsonNode data = required(payload, "data");
    for (JsonNode item : required(data, "items")) {
      UUID accountId = uuid(item, "accountId");
      JsonNode role = item.get("role");
      if (role != null && !role.isNull()) {
        authorization.upsertRole(new ProjectionRepositories.RoleProjection(
            accountId, text(role, "roleCode"), longValue(role, "roleVersion")));
      }
      for (JsonNode access : required(item, "systemAccesses")) {
        authorization.upsertAccess(new ProjectionRepositories.AccessProjection(
            accountId, uuid(access, "systemId"), text(access, "systemCode"), text(access, "status"),
            longValue(access, "accessVersion")));
      }
    }
    boolean more = booleanValue(data, "hasMore");
    String next = optionalText(data, "nextCursor");
    if (more) {
      if (next == null || next.isBlank()) {
        throw new IllegalArgumentException("AUTHORIZATION_CURSOR_REQUIRED");
      }
      jobs.advancePage(job.jobId(), next, Instant.now());
    } else {
      jobs.complete(job.jobId(), Instant.now());
      trust.recoveryCompleted("authorization");
    }
  }

  private void fail(RecoveryJobStore.Job job, String error) {
    jobs.fail(job.jobId(), error, Instant.now());
    state.mark(job.projectionName(), job.projectionName(), "DEGRADED", error, Instant.now());
  }

  private static JsonNode required(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) throw new IllegalArgumentException("FIELD_REQUIRED:" + field);
    return value;
  }

  private static String text(JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("FIELD_REQUIRED:" + field);
    return value;
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static UUID uuid(JsonNode node, String field) {
    UUID value = optionalUuid(node, field);
    if (value == null) throw new IllegalArgumentException("FIELD_REQUIRED:" + field);
    return value;
  }

  private static UUID optionalUuid(JsonNode node, String field) {
    String value = optionalText(node, field);
    return value == null || value.isBlank() ? null : UUID.fromString(value);
  }

  private static int integer(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber()) throw new IllegalArgumentException("FIELD_INTEGER_REQUIRED:" + field);
    return value.asInt();
  }

  private static long longValue(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber()) throw new IllegalArgumentException("FIELD_INTEGER_REQUIRED:" + field);
    return value.asLong();
  }

  private static boolean booleanValue(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isBoolean()) throw new IllegalArgumentException("FIELD_BOOLEAN_REQUIRED:" + field);
    return value.asBoolean();
  }

  private static Instant instant(JsonNode node, String field) {
    return Instant.parse(text(node, field));
  }

  private static Instant optionalInstant(JsonNode node, String field) {
    String value = optionalText(node, field);
    return value == null ? null : Instant.parse(value);
  }
}
