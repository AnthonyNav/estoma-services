package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleWashArrivalRegisteredUseCase;
import com.estoma.appointments.application.port.in.HandleWashEntryAuthorizedUseCase;
import com.estoma.appointments.application.port.in.HandleWashEntryRejectedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCancelledUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCompletedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionStartedUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.application.port.out.WashExecutionOperationalProjectionRepository;
import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.appointments.contracts.MessageEnvelope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Component
public class ProjectionEventDispatcher {
  private final AcademicProjectionRepository academic;
  private final AuthorizationProjectionRepository authorization;
  private final WashAdministrationProjectionRepository administration;
  private final WashExecutionOperationalProjectionRepository executionProjection;
  private final HandleWashArrivalRegisteredUseCase arrivals;
  private final HandleWashEntryAuthorizedUseCase authorized;
  private final HandleWashEntryRejectedUseCase rejected;
  private final HandleWashExecutionStartedUseCase started;
  private final HandleWashExecutionCompletedUseCase completed;
  private final HandleWashExecutionCancelledUseCase cancelled;
  private final ProjectionRecoveryCoordinator recovery;

  public ProjectionEventDispatcher(AcademicProjectionRepository academic,
      AuthorizationProjectionRepository authorization, WashAdministrationProjectionRepository administration,
      WashExecutionOperationalProjectionRepository executionProjection,
      HandleWashArrivalRegisteredUseCase arrivals, HandleWashEntryAuthorizedUseCase authorized,
      HandleWashEntryRejectedUseCase rejected, HandleWashExecutionStartedUseCase started,
      HandleWashExecutionCompletedUseCase completed, HandleWashExecutionCancelledUseCase cancelled,
      ProjectionRecoveryCoordinator recovery) {
    this.academic = academic; this.authorization = authorization; this.administration = administration;
    this.executionProjection = executionProjection; this.arrivals = arrivals; this.authorized = authorized;
    this.rejected = rejected; this.started = started; this.completed = completed; this.cancelled = cancelled; this.recovery = recovery;
  }

  @Transactional
  public void dispatch(MessageEnvelope envelope) {
    JsonNode p = envelope.payload();
    long version = envelope.aggregateVersion() == null ? 1 : envelope.aggregateVersion();
    switch (envelope.messageType()) {
      case "AcademicPeriodActivated", "AcademicPeriodClosed" -> {
        academic.upsertAcademicPeriod(new AcademicProjectionRepository.AcademicPeriodProjection(
            id(p, "academicPeriodId", envelope.aggregateId()), text(p, "code"),
            envelope.messageType().endsWith("Closed") ? "CLOSED" : textOr(p, "status", "ACTIVE"), version,
            envelope.occurredAt(), true)); recovery.ready("academic", version);
      }
      case "StudentAcademicProfileCreated", "StudentAcademicProfileActivated", "StudentAcademicProfileDeactivated", "StudentSemesterChanged" -> {
        academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
            id(p, "academicProfileId", envelope.aggregateId()), id(p, "studentAccountId", null),
            intValue(p, "currentSemester"), envelope.messageType().endsWith("Deactivated") ? "INACTIVE" : textOr(p, "academicStatus", "ACTIVE"), version,
            envelope.occurredAt(), true)); recovery.ready("academic", version);
      }
      case "CourseSectionRegistered", "CourseSectionUpdated", "CourseSectionStatusChanged", "StudentEnrolledInCourseSection", "StudentEnrollmentReactivated", "StudentRemovedFromCourseSection" -> {
        boolean removed = envelope.messageType().equals("StudentRemovedFromCourseSection") || "REMOVED".equals(textOr(p, "status", "ACTIVE"));
        academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(
            id(p, "academicProfileId", null), id(p, "studentAccountId", null), id(p, "courseSectionId", envelope.aggregateId()),
            id(p, "academicPeriodId", null), instantOr(p, "enrolledAt", envelope.occurredAt()),
            removed ? instantOr(p, "removedAt", envelope.occurredAt()) : null, version, envelope.occurredAt(), true)); recovery.ready("academic", version);
      }
      case "AccountRoleAssigned", "AccountRoleChanged" -> {
        authorization.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(
            id(p, "accountId", envelope.aggregateId()), id(p, "roleId", null), text(p, "roleCode"), version, envelope.occurredAt(), true)); recovery.ready("authorization", version);
      }
      case "SystemAccessGranted", "SystemAccessRestored", "SystemAccessSuspended", "SystemAccessRevoked" -> {
        String status = switch (envelope.messageType()) { case "SystemAccessGranted", "SystemAccessRestored" -> "ACTIVE"; case "SystemAccessSuspended" -> "SUSPENDED"; default -> "REVOKED"; };
        authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
            id(p, "systemAccessId", envelope.aggregateId()), id(p, "accountId", null), id(p, "systemId", null),
            textOr(p, "systemCode", "LAVADO_ULTRASONICO"), status, version, envelope.occurredAt(), true)); recovery.ready("authorization", version);
      }
      case "ServiceCalendarCreated", "ServiceCalendarUpdated", "ServiceCalendarActivated", "ServiceCalendarDeactivated" -> {
        administration.upsertServiceCalendar(new WashAdministrationProjectionRepository.ServiceCalendarProjection(
            id(p, "calendarId", envelope.aggregateId()), text(p, "code"), text(p, "timezone"),
            LocalDate.parse(text(p, "effectiveFrom")), optionalDate(p, "effectiveUntil"), textOr(p, "status", "ACTIVE"), version, envelope.occurredAt())); recovery.ready("wash-administration", version);
      }
      case "ServiceScheduleConfigured", "ServiceScheduleUpdated", "ServiceScheduleDeactivated" -> {
        administration.upsertServiceSchedule(new WashAdministrationProjectionRepository.ServiceScheduleProjection(
            id(p, "scheduleId", envelope.aggregateId()), id(p, "calendarId", null), intValue(p, "dayOfWeek"),
            LocalTime.parse(text(p, "openingTime")), LocalTime.parse(text(p, "closingTime")), intValue(p, "slotDurationMinutes"),
            textOr(p, "status", "ACTIVE"), version, envelope.occurredAt())); recovery.ready("wash-administration", version);
      }
      case "AdministrativeClosureScheduled", "AdministrativeClosureCancelled" -> {
        administration.upsertAdministrativeClosure(new WashAdministrationProjectionRepository.AdministrativeClosureProjection(
            id(p, "closureId", envelope.aggregateId()), id(p, "calendarId", null), text(p, "scope"), optionalUuid(p, "cabinId"), optionalUuid(p, "tankId"),
            Instant.parse(text(p, "startsAt")), Instant.parse(text(p, "endsAt")), envelope.messageType().endsWith("Cancelled") ? "CANCELLED" : "SCHEDULED", version, envelope.occurredAt())); recovery.ready("wash-administration", version);
      }
      case "CabinRegistered", "CabinUpdated", "CabinActivated", "CabinDeactivated", "CabinRetired" -> {
        administration.upsertCabin(new WashAdministrationProjectionRepository.CabinProjection(
            id(p, "cabinId", envelope.aggregateId()), text(p, "cabinType"), textOr(p, "administrativeStatus", "ACTIVE"), version, envelope.occurredAt())); recovery.ready("wash-administration", version);
      }
      case "TankRegistered", "TankUpdated", "TankActivated", "TankDeactivated", "TankRetired" -> {
        administration.upsertTank(new WashAdministrationProjectionRepository.TankProjection(
            id(p, "tankId", envelope.aggregateId()), id(p, "cabinId", null), intValue(p, "configuredCapacity"), textOr(p, "administrativeStatus", "ACTIVE"), version, envelope.occurredAt())); recovery.ready("wash-administration", version);
      }
      case "ResourcePriorityConfigured" -> {
        administration.upsertResourcePriority(new WashAdministrationProjectionRepository.ResourcePriorityProjection(
            id(p, "resourcePriorityId", envelope.aggregateId()), text(p, "appointmentType"), text(p, "cabinType"), text(p, "usageMode"), intValue(p, "priorityOrder"), textOr(p, "status", "ACTIVE"), version, envelope.occurredAt())); recovery.ready("wash-administration", version);
      }
      case "WashArrivalRegistered", "WashEntryAuthorized", "WashEntryRejected", "WashExecutionStarted", "WashExecutionCompleted", "WashExecutionCancelled" -> dispatchExecution(envelope);
      case "OperationalResourceDisabled", "OperationalResourceRestored" -> {
        executionProjection.upsertResourceUnavailability(new WashExecutionOperationalProjectionRepository.ResourceUnavailabilityProjection(
            id(p, "resourceUnavailabilityId", envelope.aggregateId()), optionalUuid(p, "cabinId"), optionalUuid(p, "tankId"),
            Instant.parse(text(p, "disabledAt")), optionalInstant(p, "restoredAt"), version, envelope.occurredAt())); recovery.ready("wash-execution", version);
      }
      case "CleaningPeriodStarted", "CleaningPeriodCompleted" -> {
        executionProjection.upsertCleaningPeriod(new WashExecutionOperationalProjectionRepository.CleaningPeriodProjection(
            id(p, "cleaningPeriodId", envelope.aggregateId()), id(p, "washExecutionId", null), id(p, "resourceAssignmentId", null), id(p, "cabinId", null),
            text(p, "cleaningTrigger"), Instant.parse(text(p, "startsAt")), Instant.parse(text(p, "expectedEndsAt")), optionalInstant(p, "completedAt"), version, envelope.occurredAt())); recovery.ready("wash-execution", version);
      }
      default -> throw new InvalidMessageException("unsupported external event " + envelope.messageType());
    }
  }

  private void dispatchExecution(MessageEnvelope e) {
    JsonNode p = e.payload();
    ExecutionEvent event = new ExecutionEvent(e.messageId(), e.messageId(), id(p, "washExecutionId", null), id(p, "appointmentId", null), e.messageType(),
        e.aggregateVersion() == null ? 1 : e.aggregateVersion(), e.occurredAt(), optionalInstant(p, "arrivedAt"), optionalInstant(p, "decidedAt"), optionalText(p, "reason"), p.toString());
    switch (e.messageType()) {
      case "WashArrivalRegistered" -> arrivals.handle(event);
      case "WashEntryAuthorized" -> authorized.handle(event);
      case "WashEntryRejected" -> rejected.handle(event);
      case "WashExecutionStarted" -> started.handle(event);
      case "WashExecutionCompleted" -> completed.handle(event);
      case "WashExecutionCancelled" -> cancelled.handle(event);
      default -> throw new InvalidMessageException("unsupported Execution event");
    }
    recovery.ready("wash-execution", event.executionVersion());
  }

  private static UUID id(JsonNode p, String field, UUID fallback) { UUID value = optionalUuid(p, field); if (value == null && fallback != null) return fallback; if (value == null) throw new InvalidMessageException(field + " is required"); return value; }
  private static UUID optionalUuid(JsonNode p, String field) { String value = optionalText(p, field); return value == null ? null : UUID.fromString(value); }
  private static String text(JsonNode p, String field) { String value = optionalText(p, field); if (value == null || value.isBlank()) throw new InvalidMessageException(field + " is required"); return value; }
  private static String textOr(JsonNode p, String field, String fallback) { String value = optionalText(p, field); return value == null ? fallback : value; }
  private static String optionalText(JsonNode p, String field) { return !p.has(field) || p.path(field).isNull() || !p.path(field).isTextual() ? null : p.path(field).asText(); }
  private static int intValue(JsonNode p, String field) { if (!p.has(field) || !p.path(field).isInt()) throw new InvalidMessageException(field + " must be an integer"); return p.path(field).asInt(); }
  private static Instant instantOr(JsonNode p, String field, Instant fallback) { Instant value = optionalInstant(p, field); return value == null ? fallback : value; }
  private static Instant optionalInstant(JsonNode p, String field) { String value = optionalText(p, field); return value == null ? null : Instant.parse(value); }
  private static LocalDate optionalDate(JsonNode p, String field) { String value = optionalText(p, field); return value == null ? null : LocalDate.parse(value); }
}
