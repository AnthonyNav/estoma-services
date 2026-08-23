package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CabinType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class ProjectionEventProcessor {
  public record Outcome(boolean applied, String deferredReason) {
    static Outcome success() { return new Outcome(true, null); }
    static Outcome deferred(String reason) { return new Outcome(false, reason); }
  }

  private final ProjectionRepositories.AppointmentRepository appointments;
  private final ProjectionRepositories.TimeSlotRepository slots;
  private final ProjectionRepositories.ResourceProjectionRepository resources;
  private final ProjectionRepositories.AdministrativeClosureRepository closures;
  private final ProjectionRepositories.AuthorizationProjectionRepository authorization;

  public ProjectionEventProcessor(
      ProjectionRepositories.AppointmentRepository appointments,
      ProjectionRepositories.TimeSlotRepository slots,
      ProjectionRepositories.ResourceProjectionRepository resources,
      ProjectionRepositories.AdministrativeClosureRepository closures,
      ProjectionRepositories.AuthorizationProjectionRepository authorization) {
    this.appointments = appointments;
    this.slots = slots;
    this.resources = resources;
    this.closures = closures;
    this.authorization = authorization;
  }

  public Outcome process(MessageEnvelope e) {
    long version = sourceVersion(e);
    return switch (e.messageType()) {
      case "AppointmentScheduled" -> appointmentScheduled(e.payload(), version);
      case "AppointmentCancelled" -> updateAppointmentStatus(e.payload(), version, "CANCELLED");
      case "AppointmentMissed" -> updateAppointmentStatus(e.payload(), version, "MISSED");
      case "AppointmentTimeSlotCreated" -> slotCreated(e.payload(), version);
      case "AppointmentTimeSlotCancelled" -> slotCancelled(e.payload(), version);
      case "CabinRegistered" -> cabinRegistered(e.payload(), version);
      case "CabinUpdated" -> cabinUpdated(e.payload(), version);
      case "CabinActivated" -> cabinStatus(e.payload(), version, "ACTIVE");
      case "CabinDeactivated" -> cabinStatus(e.payload(), version, "INACTIVE");
      case "CabinRetired" -> cabinStatus(e.payload(), version, "RETIRED");
      case "TankRegistered" -> tankRegistered(e.payload(), version);
      case "TankUpdated" -> tankUpdated(e.payload(), version);
      case "TankActivated" -> tankStatus(e.payload(), version, "ACTIVE");
      case "TankDeactivated" -> tankStatus(e.payload(), version, "INACTIVE");
      case "TankRetired" -> tankStatus(e.payload(), version, "RETIRED");
      case "ResourcePriorityConfigured" -> priorityConfigured(e.payload(), version);
      case "AdministrativeClosureScheduled" -> closureScheduled(e.payload(), version);
      case "AdministrativeClosureCancelled" -> closureCancelled(e.payload(), version);
      case "AccountRoleAssigned" -> roleAssigned(e.payload(), version);
      case "AccountRoleChanged" -> roleChanged(e.payload(), version);
      case "SystemAccessGranted", "SystemAccessSuspended", "SystemAccessRevoked", "SystemAccessRestored" ->
          accessChanged(e.payload(), version);
      default -> throw new IllegalArgumentException("UNSUPPORTED_EVENT_TYPE:" + e.messageType());
    };
  }

  private Outcome appointmentScheduled(JsonNode p, long version) {
    UUID id = uuid(p, "appointmentId");
    var current = appointments.findById(id);
    if (current.isPresent() && current.get().sourceVersion() >= version) return Outcome.success();
    appointments.upsert(new ProjectionRepositories.AppointmentContext(
        id, uuid(p, "studentAccountId"), uuid(p, "appointmentTimeSlotId"), uuid(p, "courseSectionId"),
        AppointmentType.valueOf(text(p, "appointmentType")), integer(p, "instrumentCount"), text(p, "pieceType"),
        "SCHEDULED", null, version));
    return Outcome.success();
  }

  private Outcome updateAppointmentStatus(JsonNode p, long version, String status) {
    UUID id = uuid(p, "appointmentId");
    var current = appointments.findById(id);
    if (current.isEmpty()) return Outcome.deferred("APPOINTMENT_PREREQUISITE_MISSING");
    if (current.get().sourceVersion() >= version) return Outcome.success();
    var c = current.get();
    appointments.upsert(new ProjectionRepositories.AppointmentContext(
        c.appointmentId(), c.studentAccountId(), c.appointmentTimeSlotId(), c.courseSectionId(),
        c.appointmentType(), c.instrumentCount(), c.pieceType(), status, c.entryAuthorizedAt(), version));
    return Outcome.success();
  }

  private Outcome slotCreated(JsonNode p, long version) {
    UUID id = uuid(p, "appointmentTimeSlotId");
    var current = slots.findById(id);
    if (current.isPresent() && current.get().sourceVersion() >= version) return Outcome.success();
    UUID calendarId = optionalUuid(p, "calendarId");
    if (calendarId == null) return Outcome.deferred("APPOINTMENT_SLOT_CALENDAR_CONTEXT_MISSING");
    slots.upsert(new ProjectionRepositories.TimeSlotContext(
        id, calendarId, LocalDate.parse(text(p, "serviceDate")), instant(p, "startsAt"), instant(p, "endsAt"),
        text(p, "timezone"), instant(p, "arrivalToleranceUntil"), text(p, "status"), version));
    return Outcome.success();
  }

  private Outcome slotCancelled(JsonNode p, long version) {
    UUID id = uuid(p, "appointmentTimeSlotId");
    var current = slots.findById(id);
    if (current.isEmpty()) return Outcome.deferred("TIME_SLOT_PREREQUISITE_MISSING");
    if (current.get().sourceVersion() >= version) return Outcome.success();
    var c = current.get();
    slots.upsert(new ProjectionRepositories.TimeSlotContext(
        c.appointmentTimeSlotId(), c.calendarId(), c.serviceDate(), c.startsAt(), c.endsAt(), c.timezone(),
        c.arrivalToleranceUntil(), "CANCELLED", version));
    return Outcome.success();
  }

  private Outcome cabinRegistered(JsonNode p, long version) {
    UUID id = uuid(p, "cabinId");
    var current = resources.findCabin(id);
    if (current.isPresent() && current.get().sourceVersion() >= version) return Outcome.success();
    resources.upsertCabin(new ProjectionRepositories.CabinProjection(
        id, text(p, "code"), optionalText(p, "name"), CabinType.valueOf(text(p, "cabinType")),
        text(p, "status"), version));
    return Outcome.success();
  }

  private Outcome cabinUpdated(JsonNode p, long version) {
    UUID id = uuid(p, "cabinId");
    var current = resources.findCabin(id);
    if (current.isEmpty()) return Outcome.deferred("CABIN_PREREQUISITE_MISSING");
    if (current.get().sourceVersion() >= version) return Outcome.success();
    var c = current.get();
    resources.upsertCabin(new ProjectionRepositories.CabinProjection(
        id, c.code(), optionalText(p, "name"), CabinType.valueOf(text(p, "cabinType")), text(p, "status"), version));
    return Outcome.success();
  }

  private Outcome cabinStatus(JsonNode p, long version, String status) {
    UUID id = uuid(p, "cabinId");
    var current = resources.findCabin(id);
    if (current.isEmpty()) return Outcome.deferred("CABIN_PREREQUISITE_MISSING");
    if (current.get().sourceVersion() >= version) return Outcome.success();
    var c = current.get();
    resources.upsertCabin(new ProjectionRepositories.CabinProjection(
        id, c.code(), c.name(), c.type(), status, version));
    return Outcome.success();
  }

  private Outcome tankRegistered(JsonNode p, long version) {
    UUID id = uuid(p, "tankId");
    var current = resources.findTank(id);
    if (current.isPresent() && current.get().sourceVersion() >= version) return Outcome.success();
    resources.upsertTank(new ProjectionRepositories.TankProjection(
        id, uuid(p, "cabinId"), text(p, "code"), optionalText(p, "name"), integer(p, "configuredCapacity"),
        text(p, "status"), version));
    return Outcome.success();
  }

  private Outcome tankUpdated(JsonNode p, long version) {
    UUID id = uuid(p, "tankId");
    var current = resources.findTank(id);
    if (current.isEmpty()) return Outcome.deferred("TANK_PREREQUISITE_MISSING");
    if (current.get().sourceVersion() >= version) return Outcome.success();
    var c = current.get();
    UUID cabinId = optionalUuid(p, "cabinId");
    resources.upsertTank(new ProjectionRepositories.TankProjection(
        id, cabinId == null ? c.cabinId() : cabinId, c.code(), optionalText(p, "name"),
        integer(p, "configuredCapacity"), text(p, "status"), version));
    return Outcome.success();
  }

  private Outcome tankStatus(JsonNode p, long version, String status) {
    UUID id = uuid(p, "tankId");
    var current = resources.findTank(id);
    if (current.isEmpty()) return Outcome.deferred("TANK_PREREQUISITE_MISSING");
    if (current.get().sourceVersion() >= version) return Outcome.success();
    var c = current.get();
    resources.upsertTank(new ProjectionRepositories.TankProjection(
        id, c.cabinId(), c.code(), c.name(), c.configuredCapacity(), status, version));
    return Outcome.success();
  }

  private Outcome priorityConfigured(JsonNode p, long version) {
    resources.upsertPriority(new ProjectionRepositories.ResourcePriorityProjection(
        uuid(p, "resourcePriorityId"), AppointmentType.valueOf(text(p, "appointmentType")),
        CabinType.valueOf(text(p, "cabinType")), text(p, "usageMode"), integer(p, "priorityOrder"),
        text(p, "status"), version));
    return Outcome.success();
  }

  private Outcome closureScheduled(JsonNode p, long version) {
    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        uuid(p, "closureId"), uuid(p, "calendarId"), text(p, "scope"), optionalUuid(p, "cabinId"),
        optionalUuid(p, "tankId"), instant(p, "startsAt"), instant(p, "endsAt"), text(p, "status"), version));
    return Outcome.success();
  }

  private Outcome closureCancelled(JsonNode p, long version) {
    UUID id = uuid(p, "closureId");
    var current = closures.findById(id);
    if (current.isEmpty()) return Outcome.deferred("CLOSURE_PREREQUISITE_MISSING");
    if (current.get().sourceVersion() >= version) return Outcome.success();
    var c = current.get();
    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        id, c.calendarId(), c.scope(), c.cabinId(), c.tankId(), c.startsAt(), c.endsAt(), "CANCELLED", version));
    return Outcome.success();
  }

  private Outcome roleAssigned(JsonNode p, long version) {
    authorization.upsertRole(new ProjectionRepositories.RoleProjection(
        uuid(p, "accountId"), text(p, "roleCode"), version));
    return Outcome.success();
  }

  private Outcome roleChanged(JsonNode p, long version) {
    authorization.upsertRole(new ProjectionRepositories.RoleProjection(
        uuid(p, "accountId"), text(p, "newRoleCode"), version));
    return Outcome.success();
  }

  private Outcome accessChanged(JsonNode p, long version) {
    authorization.upsertAccess(new ProjectionRepositories.AccessProjection(
        uuid(p, "accountId"), uuid(p, "systemId"), text(p, "systemCode"), text(p, "status"), version));
    return Outcome.success();
  }

  private static long sourceVersion(MessageEnvelope e) {
    if (e.aggregateVersion() == null || e.aggregateVersion() < 1) throw new IllegalArgumentException("SOURCE_VERSION_REQUIRED");
    return e.aggregateVersion();
  }

  private static String text(JsonNode p, String field) {
    String value = optionalText(p, field);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("FIELD_REQUIRED:" + field);
    return value;
  }

  private static String optionalText(JsonNode p, String field) {
    JsonNode value = p.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static UUID uuid(JsonNode p, String field) {
    UUID value = optionalUuid(p, field);
    if (value == null) throw new IllegalArgumentException("FIELD_REQUIRED:" + field);
    return value;
  }

  private static UUID optionalUuid(JsonNode p, String field) {
    String value = optionalText(p, field);
    return value == null || value.isBlank() ? null : UUID.fromString(value);
  }

  private static int integer(JsonNode p, String field) {
    JsonNode value = p.get(field);
    if (value == null || !value.isIntegralNumber()) throw new IllegalArgumentException("FIELD_REQUIRED:" + field);
    return value.asInt();
  }

  private static Instant instant(JsonNode p, String field) { return Instant.parse(text(p, field)); }
}
