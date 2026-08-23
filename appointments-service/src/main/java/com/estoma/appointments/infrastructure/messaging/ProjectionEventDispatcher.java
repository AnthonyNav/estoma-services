package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleResourceCapacityEventUseCase;
import com.estoma.appointments.application.port.in.HandleWashArrivalRegisteredUseCase;
import com.estoma.appointments.application.port.in.HandleWashEntryAuthorizedUseCase;
import com.estoma.appointments.application.port.in.HandleWashEntryRejectedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCancelledUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCompletedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionStartedUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.PendingProjectionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.application.port.out.WashExecutionOperationalProjectionRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Maps authoritative external Events into local projections. Snapshot completeness/readiness is
 * owned by recovery; incremental Events never promote a projection to READY by themselves.
 */
@Component
public class ProjectionEventDispatcher {
  private final AcademicProjectionRepository academic;
  private final AuthorizationProjectionRepository authorization;
  private final WashAdministrationProjectionRepository administration;
  private final WashExecutionOperationalProjectionRepository executionProjection;
  private final HandleResourceCapacityEventUseCase capacityEvents;
  private final HandleWashArrivalRegisteredUseCase arrivals;
  private final HandleWashEntryAuthorizedUseCase authorized;
  private final HandleWashEntryRejectedUseCase rejected;
  private final HandleWashExecutionStartedUseCase started;
  private final HandleWashExecutionCompletedUseCase completed;
  private final HandleWashExecutionCancelledUseCase cancelled;
  private final ProcessedMessageRepository inbox;
  private final PendingProjectionEventRepository pending;
  private final EnvelopeJsonCodec codec;

  public ProjectionEventDispatcher(AcademicProjectionRepository academic,
      AuthorizationProjectionRepository authorization,
      WashAdministrationProjectionRepository administration,
      WashExecutionOperationalProjectionRepository executionProjection,
      HandleResourceCapacityEventUseCase capacityEvents,
      HandleWashArrivalRegisteredUseCase arrivals,
      HandleWashEntryAuthorizedUseCase authorized,
      HandleWashEntryRejectedUseCase rejected,
      HandleWashExecutionStartedUseCase started,
      HandleWashExecutionCompletedUseCase completed,
      HandleWashExecutionCancelledUseCase cancelled,
      ProcessedMessageRepository inbox,
      PendingProjectionEventRepository pending,
      EnvelopeJsonCodec codec) {
    this.academic = academic;
    this.authorization = authorization;
    this.administration = administration;
    this.executionProjection = executionProjection;
    this.capacityEvents = capacityEvents;
    this.arrivals = arrivals;
    this.authorized = authorized;
    this.rejected = rejected;
    this.started = started;
    this.completed = completed;
    this.cancelled = cancelled;
    this.inbox = inbox;
    this.pending = pending;
    this.codec = codec;
  }

  @Transactional
  public void dispatch(MessageEnvelope envelope) {
    if (isExecutionAppointmentEvent(envelope.messageType())) {
      dispatchExecution(envelope);
      return;
    }

    Instant now = envelope.occurredAt();
    if (!inbox.tryClaim(envelope.messageId(), envelope.messageType(), now)) return;
    String projection = projectionName(envelope.messageType());
    try {
      switch (projection) {
        case "academic" -> applyAcademic(envelope);
        case "authorization" -> applyAuthorization(envelope);
        case "wash-administration" -> applyAdministration(envelope);
        case "wash-execution" -> applyExecutionOperational(envelope);
        default -> throw new InvalidMessageException("unsupported projection " + projection);
      }
      inbox.markApplied(envelope.messageId(), now);
      pending.completeBySourceEvent(envelope.messageId(), now);
    } catch (ProjectionDependencyMissing dependency) {
      pending.defer(new PendingProjectionEventRepository.PendingProjectionEvent(
          UUID.randomUUID(), envelope.messageId(), projection, envelope.messageType(),
          envelope.aggregateVersion() == null ? null : envelope.aggregateVersion().longValue(),
          new String(codec.encode(envelope), StandardCharsets.UTF_8), "PENDING", 0, now, null, null));
      inbox.markDeferred(envelope.messageId(), dependency.getMessage(), now);
    }
  }

  private void applyAcademic(MessageEnvelope e) {
    JsonNode p = e.payload();
    long v = version(e);
    switch (e.messageType()) {
      case "AcademicPeriodActivated" -> academic.upsertAcademicPeriod(
          new AcademicProjectionRepository.AcademicPeriodProjection(
              id(p,"academicPeriodId",e.aggregateId()), text(p,"code"), "ACTIVE", v, e.occurredAt(), true));
      case "AcademicPeriodClosed" -> academic.upsertAcademicPeriod(
          new AcademicProjectionRepository.AcademicPeriodProjection(
              id(p,"academicPeriodId",e.aggregateId()), text(p,"code"), "CLOSED", v, e.occurredAt(), true));
      case "StudentAcademicProfileCreated" -> academic.upsertStudentProfile(
          new AcademicProjectionRepository.StudentAcademicProfileProjection(
              id(p,"studentAcademicProfileId",e.aggregateId()), id(p,"accountId",null),
              intValue(p,"currentSemester"), text(p,"academicStatus"), v, e.occurredAt(), true));
      case "StudentAcademicProfileActivated", "StudentAcademicProfileDeactivated" -> {
        UUID accountId = id(p,"accountId",null);
        var current = academic.findStudentProfile(accountId)
            .orElseThrow(() -> missing("academic", e, "student profile base is absent"));
        UUID profileId = id(p,"studentAcademicProfileId",e.aggregateId());
        if (!current.academicProfileId().equals(profileId)) throw new InvalidMessageException("academic profile identity mismatch");
        academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
            profileId, accountId, current.currentSemester(), text(p,"academicStatus"), v, e.occurredAt(), true));
      }
      case "StudentSemesterChanged" -> {
        UUID accountId = id(p,"accountId",null);
        var current = academic.findStudentProfile(accountId)
            .orElseThrow(() -> missing("academic", e, "student profile base is absent"));
        UUID profileId = id(p,"studentAcademicProfileId",e.aggregateId());
        if (!current.academicProfileId().equals(profileId)) throw new InvalidMessageException("academic profile identity mismatch");
        academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
            profileId, accountId, intValue(p,"currentSemester"), current.academicStatus(), v,
            e.occurredAt(), true));
      }
      case "CourseSectionRegistered" -> academic.upsertCourseSection(
          new AcademicProjectionRepository.CourseSectionProjection(
              id(p,"courseSectionId",e.aggregateId()), id(p,"academicPeriodId",null),
              text(p,"status"), v, e.occurredAt(), true));
      case "CourseSectionUpdated" -> {
        UUID sectionId = id(p,"courseSectionId",e.aggregateId());
        var current = academic.findCourseSection(sectionId)
            .orElseThrow(() -> missing("academic", e, "course section base is absent"));
        academic.upsertCourseSection(new AcademicProjectionRepository.CourseSectionProjection(
            sectionId, id(p,"academicPeriodId",current.academicPeriodId()), current.status(), v,
            e.occurredAt(), true));
      }
      case "CourseSectionStatusChanged" -> academic.upsertCourseSection(
          new AcademicProjectionRepository.CourseSectionProjection(
              id(p,"courseSectionId",e.aggregateId()), id(p,"academicPeriodId",null),
              text(p,"currentStatus"), v, e.occurredAt(), true));
      case "StudentEnrolledInCourseSection" -> academic.upsertStudentCourse(
          new AcademicProjectionRepository.StudentCourseSectionProjection(
              id(p,"studentAcademicProfileId",null), id(p,"accountId",null),
              id(p,"courseSectionId",e.aggregateId()), id(p,"academicPeriodId",null),
              instant(p,"enrolledAt"), null, v, e.occurredAt(), true));
      case "StudentEnrollmentReactivated" -> {
        UUID accountId = id(p,"accountId",null);
        UUID sectionId = id(p,"courseSectionId",e.aggregateId());
        var current = academic.findStudentCourse(accountId, sectionId)
            .orElseThrow(() -> missing("academic", e, "enrollment base is absent"));
        academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(
            id(p,"studentAcademicProfileId",current.academicProfileId()), accountId, sectionId,
            id(p,"academicPeriodId",current.academicPeriodId()), current.enrolledAt(), null, v,
            e.occurredAt(), true));
      }
      case "StudentRemovedFromCourseSection" -> {
        UUID accountId = id(p,"accountId",null);
        UUID sectionId = id(p,"courseSectionId",e.aggregateId());
        var current = academic.findStudentCourse(accountId, sectionId)
            .orElseThrow(() -> missing("academic", e, "enrollment base is absent"));
        academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(
            id(p,"studentAcademicProfileId",current.academicProfileId()), accountId, sectionId,
            id(p,"academicPeriodId",current.academicPeriodId()), current.enrolledAt(),
            instant(p,"removedAt"), v, e.occurredAt(), true));
      }
      default -> throw new InvalidMessageException("unsupported Academic event " + e.messageType());
    }
  }

  private void applyAuthorization(MessageEnvelope e) {
    JsonNode p = e.payload();
    long v = version(e);
    switch (e.messageType()) {
      case "AccountRoleAssigned" -> authorization.upsertAccountRole(
          new AuthorizationProjectionRepository.AccountRoleProjection(
              id(p,"accountId",e.aggregateId()), id(p,"roleId",null), text(p,"roleCode"), v,
              e.occurredAt(), true));
      case "AccountRoleChanged" -> authorization.upsertAccountRole(
          new AuthorizationProjectionRepository.AccountRoleProjection(
              id(p,"accountId",e.aggregateId()), id(p,"newRoleId",null), text(p,"newRoleCode"), v,
              e.occurredAt(), true));
      case "SystemAccessGranted", "SystemAccessSuspended", "SystemAccessRevoked", "SystemAccessRestored" ->
          authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
              id(p,"systemAccessId",e.aggregateId()), id(p,"accountId",null), id(p,"systemId",null),
              text(p,"systemCode"), text(p,"status"), v, e.occurredAt(), true));
      default -> throw new InvalidMessageException("unsupported Authorization event " + e.messageType());
    }
  }

  private void applyAdministration(MessageEnvelope e) {
    JsonNode p = e.payload();
    long v = version(e);
    Instant from = e.occurredAt();
    Instant until = null;
    switch (e.messageType()) {
      case "ServiceCalendarCreated" -> administration.upsertServiceCalendar(
          new WashAdministrationProjectionRepository.ServiceCalendarProjection(
              id(p,"calendarId",e.aggregateId()), text(p,"code"), text(p,"timezone"),
              LocalDate.parse(text(p,"effectiveFrom")), optionalDate(p,"effectiveUntil"),
              text(p,"status"), v, e.occurredAt()));
      case "ServiceCalendarUpdated" -> {
        UUID id = id(p,"calendarId",e.aggregateId());
        var current = administration.findCalendar(id)
            .orElseThrow(() -> missing("wash-administration", e, "calendar base is absent"));
        administration.upsertServiceCalendar(new WashAdministrationProjectionRepository.ServiceCalendarProjection(
            id, current.code(), text(p,"timezone"), LocalDate.parse(text(p,"effectiveFrom")),
            optionalDate(p,"effectiveUntil"), text(p,"status"), v, e.occurredAt()));
      }
      case "ServiceCalendarActivated", "ServiceCalendarDeactivated" -> {
        UUID id = id(p,"calendarId",e.aggregateId());
        var current = administration.findCalendar(id)
            .orElseThrow(() -> missing("wash-administration", e, "calendar base is absent"));
        administration.upsertServiceCalendar(new WashAdministrationProjectionRepository.ServiceCalendarProjection(
            id,current.code(),current.timezone(),current.effectiveFrom(),current.effectiveUntil(),
            text(p,"status"),v,e.occurredAt()));
      }
      case "ServiceScheduleConfigured", "ServiceScheduleUpdated" -> administration.upsertServiceSchedule(
          new WashAdministrationProjectionRepository.ServiceScheduleProjection(
              id(p,"scheduleId",e.aggregateId()),id(p,"calendarId",null),intValue(p,"dayOfWeek"),
              LocalTime.parse(text(p,"openingTime")),LocalTime.parse(text(p,"closingTime")),
              intValue(p,"slotDurationMinutes"),text(p,"status"),v,e.occurredAt()));
      case "ServiceScheduleDeactivated" -> {
        UUID id = id(p,"scheduleId",e.aggregateId());
        var current = administration.findSchedule(id)
            .orElseThrow(() -> missing("wash-administration", e, "schedule base is absent"));
        administration.upsertServiceSchedule(new WashAdministrationProjectionRepository.ServiceScheduleProjection(
            id,current.calendarId(),current.dayOfWeek(),current.openingTime(),current.closingTime(),
            current.slotDurationMinutes(),text(p,"status"),v,e.occurredAt()));
      }
      case "AdministrativeClosureScheduled" -> {
        from = instant(p,"startsAt");
        until = instant(p,"endsAt");
        administration.upsertAdministrativeClosure(new WashAdministrationProjectionRepository.AdministrativeClosureProjection(
            id(p,"closureId",e.aggregateId()),id(p,"calendarId",null),text(p,"scope"),
            optionalUuid(p,"cabinId"),optionalUuid(p,"tankId"),from,until,text(p,"status"),v,e.occurredAt()));
      }
      case "AdministrativeClosureCancelled" -> {
        UUID id = id(p,"closureId",e.aggregateId());
        var current = administration.findClosure(id)
            .orElseThrow(() -> missing("wash-administration", e, "closure base is absent"));
        from = current.startsAt(); until = current.endsAt();
        administration.upsertAdministrativeClosure(new WashAdministrationProjectionRepository.AdministrativeClosureProjection(
            id,current.calendarId(),current.scope(),current.cabinId(),current.tankId(),current.startsAt(),
            current.endsAt(),text(p,"status"),v,e.occurredAt()));
      }
      case "CabinRegistered", "CabinUpdated" -> administration.upsertCabin(
          new WashAdministrationProjectionRepository.CabinProjection(
              id(p,"cabinId",e.aggregateId()),text(p,"cabinType"),text(p,"status"),v,e.occurredAt()));
      case "CabinActivated", "CabinDeactivated", "CabinRetired" -> {
        UUID id = id(p,"cabinId",e.aggregateId());
        var current = administration.findCabin(id)
            .orElseThrow(() -> missing("wash-administration", e, "cabin base is absent"));
        administration.upsertCabin(new WashAdministrationProjectionRepository.CabinProjection(
            id,current.cabinType(),text(p,"status"),v,e.occurredAt()));
      }
      case "TankRegistered", "TankUpdated" -> administration.upsertTank(
          new WashAdministrationProjectionRepository.TankProjection(
              id(p,"tankId",e.aggregateId()),id(p,"cabinId",null),intValue(p,"configuredCapacity"),
              text(p,"status"),v,e.occurredAt()));
      case "TankActivated", "TankDeactivated", "TankRetired" -> {
        UUID id = id(p,"tankId",e.aggregateId());
        var current = administration.findTank(id)
            .orElseThrow(() -> missing("wash-administration", e, "tank base is absent"));
        administration.upsertTank(new WashAdministrationProjectionRepository.TankProjection(
            id,current.cabinId(),current.configuredCapacity(),text(p,"status"),v,e.occurredAt()));
      }
      case "ResourcePriorityConfigured" -> administration.upsertResourcePriority(
          new WashAdministrationProjectionRepository.ResourcePriorityProjection(
              id(p,"resourcePriorityId",e.aggregateId()),text(p,"appointmentType"),text(p,"cabinType"),
              text(p,"usageMode"),intValue(p,"priorityOrder"),text(p,"status"),v,e.occurredAt()));
      default -> throw new InvalidMessageException("unsupported Wash Administration event " + e.messageType());
    }
    requestCapacityRecalculation(e, from, until);
  }

  private void applyExecutionOperational(MessageEnvelope e) {
    JsonNode p = e.payload();
    long v = version(e);
    Instant from;
    Instant until;
    switch (e.messageType()) {
      case "OperationalResourceDisabled" -> {
        from = instant(p,"disabledAt"); until = null;
        executionProjection.upsertResourceUnavailability(
            new WashExecutionOperationalProjectionRepository.ResourceUnavailabilityProjection(
                id(p,"resourceUnavailabilityId",e.aggregateId()),optionalUuid(p,"cabinId"),optionalUuid(p,"tankId"),
                from,null,v,e.occurredAt()));
      }
      case "OperationalResourceRestored" -> {
        UUID id = id(p,"resourceUnavailabilityId",e.aggregateId());
        var current = executionProjection.findResourceUnavailability(id)
            .orElseThrow(() -> missing("wash-execution", e, "resource unavailability base is absent"));
        from = instant(p,"restoredAt"); until = null;
        executionProjection.upsertResourceUnavailability(
            new WashExecutionOperationalProjectionRepository.ResourceUnavailabilityProjection(
                id,current.cabinId(),current.tankId(),current.disabledAt(),from,v,e.occurredAt()));
      }
      case "CleaningPeriodStarted" -> {
        from = instant(p,"startsAt"); until = instant(p,"expectedEndsAt");
        executionProjection.upsertCleaningPeriod(
            new WashExecutionOperationalProjectionRepository.CleaningPeriodProjection(
                id(p,"cleaningPeriodId",e.aggregateId()),id(p,"washExecutionId",null),
                id(p,"resourceAssignmentId",null),id(p,"cabinId",null),text(p,"cleaningTrigger"),
                from,until,null,v,e.occurredAt()));
      }
      case "CleaningPeriodCompleted" -> {
        UUID id = id(p,"cleaningPeriodId",e.aggregateId());
        var current = executionProjection.findCleaningPeriod(id)
            .orElseThrow(() -> missing("wash-execution", e, "cleaning period base is absent"));
        from = current.startsAt(); until = current.expectedEndsAt();
        executionProjection.upsertCleaningPeriod(
            new WashExecutionOperationalProjectionRepository.CleaningPeriodProjection(
                id,current.washExecutionId(),current.resourceAssignmentId(),current.cabinId(),
                current.cleaningTrigger(),current.startsAt(),current.expectedEndsAt(),
                instant(p,"completedAt"),v,e.occurredAt()));
      }
      default -> throw new InvalidMessageException("unsupported Wash Execution operational event " + e.messageType());
    }
    requestCapacityRecalculation(e, from, until);
  }

  private void dispatchExecution(MessageEnvelope e) {
    JsonNode p = e.payload();
    long v = version(e);
    ExecutionEvent event = switch (e.messageType()) {
      case "WashArrivalRegistered" -> executionEvent(e,v,optionalInstant(p,"arrivedAt"),null,null,null,null,null,null);
      case "WashEntryAuthorized" -> executionEvent(e,v,null,optionalInstant(p,"decidedAt"),optionalUuid(p,"decidedByAccountId"),null,null,null,null);
      case "WashEntryRejected" -> executionEvent(e,v,null,optionalInstant(p,"decidedAt"),optionalUuid(p,"decidedByAccountId"),null,null,null,optionalText(p,"rejectionReason"));
      case "WashExecutionStarted" -> executionEvent(e,v,null,optionalInstant(p,"startedAt"),null,null,null,null,null);
      case "WashExecutionCompleted" -> executionEvent(e,v,null,optionalInstant(p,"completedAt"),optionalUuid(p,"completedByAccountId"),null,null,null,null);
      case "WashExecutionCancelled" -> executionEvent(e,v,null,optionalInstant(p,"cancelledAt"),optionalUuid(p,"cancelledByAccountId"),optionalInstant(p,"startedAt"),text(p,"cancellationType"),text(p,"cancellationSubreason"),optionalText(p,"cancellationReason"));
      default -> throw new InvalidMessageException("unsupported Execution event " + e.messageType());
    };
    switch (e.messageType()) {
      case "WashArrivalRegistered" -> arrivals.handle(event);
      case "WashEntryAuthorized" -> authorized.handle(event);
      case "WashEntryRejected" -> rejected.handle(event);
      case "WashExecutionStarted" -> started.handle(event);
      case "WashExecutionCompleted" -> completed.handle(event);
      case "WashExecutionCancelled" -> cancelled.handle(event);
      default -> throw new InvalidMessageException("unsupported Execution event");
    }
  }

  private ExecutionEvent executionEvent(MessageEnvelope e, long version, Instant arrivedAt,
      Instant actionAt, UUID actorAccountId, Instant startedAt, String cancellationType,
      String cancellationSubreason, String reason) {
    JsonNode p = e.payload();
    return new ExecutionEvent(e.messageId(),e.messageId(),id(p,"washExecutionId",e.aggregateId()),
        id(p,"appointmentId",null),e.messageType(),version,e.occurredAt(),arrivedAt,actionAt,
        actorAccountId,startedAt,cancellationType,cancellationSubreason,reason,p.toString(),
        e.correlationId());
  }

  private void requestCapacityRecalculation(MessageEnvelope e, Instant from, Instant until) {
    CommandMetadata metadata = new CommandMetadata(e.messageId(), "capacity-event:" + e.messageId(),
        e.correlationId(), e.messageId(), ActorContext.system("appointments"), e.occurredAt());
    capacityEvents.handle(new HandleResourceCapacityEventUseCase.Command(
        metadata,e.messageId(),from,until,e.occurredAt()));
  }

  private String projectionName(String type) {
    if (type.startsWith("AcademicPeriod") || type.startsWith("StudentAcademic")
        || type.startsWith("StudentSemester") || type.startsWith("CourseSection")
        || type.startsWith("StudentEnrolled") || type.startsWith("StudentEnrollment")
        || type.startsWith("StudentRemoved")) return "academic";
    if (type.startsWith("AccountRole") || type.startsWith("SystemAccess")) return "authorization";
    if (type.startsWith("ServiceCalendar") || type.startsWith("ServiceSchedule")
        || type.startsWith("AdministrativeClosure") || type.startsWith("Cabin")
        || type.startsWith("Tank") || type.startsWith("ResourcePriority")) return "wash-administration";
    if (type.startsWith("OperationalResource") || type.startsWith("CleaningPeriod")) return "wash-execution";
    throw new InvalidMessageException("unsupported external event " + type);
  }

  private static boolean isExecutionAppointmentEvent(String type) {
    return switch (type) {
      case "WashArrivalRegistered", "WashEntryAuthorized", "WashEntryRejected",
           "WashExecutionStarted", "WashExecutionCompleted", "WashExecutionCancelled" -> true;
      default -> false;
    };
  }

  private static ProjectionDependencyMissing missing(String projection, MessageEnvelope e, String reason) {
    return new ProjectionDependencyMissing(projection + ": " + e.messageType() + ": " + reason);
  }
  private static long version(MessageEnvelope e) {
    if (e.aggregateVersion() == null || e.aggregateVersion() <= 0)
      throw new InvalidMessageException("aggregateVersion must be positive for projection events");
    return e.aggregateVersion().longValue();
  }
  private static UUID id(JsonNode p, String field, UUID fallback) {
    UUID value = optionalUuid(p,field);
    if (value == null) value = fallback;
    if (value == null) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static UUID optionalUuid(JsonNode p, String field) {
    String value = optionalText(p,field);
    if (value == null) return null;
    try { return UUID.fromString(value); }
    catch (IllegalArgumentException ex) { throw new InvalidMessageException(field + " must be UUID", ex); }
  }
  private static String text(JsonNode p, String field) {
    String value = optionalText(p,field);
    if (value == null || value.isBlank()) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static String optionalText(JsonNode p, String field) {
    return !p.has(field) || p.path(field).isNull() || !p.path(field).isTextual() ? null : p.path(field).asString();
  }
  private static int intValue(JsonNode p, String field) {
    if (!p.has(field) || !p.path(field).canConvertToInt()) throw new InvalidMessageException(field + " must be integer");
    return p.path(field).asInt();
  }
  private static Instant instant(JsonNode p, String field) {
    Instant value = optionalInstant(p,field);
    if (value == null) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static Instant optionalInstant(JsonNode p, String field) {
    String value = optionalText(p,field);
    if (value == null) return null;
    try { return Instant.parse(value); }
    catch (RuntimeException ex) { throw new InvalidMessageException(field + " must be ISO-8601 instant", ex); }
  }
  private static LocalDate optionalDate(JsonNode p, String field) {
    String value = optionalText(p,field);
    if (value == null) return null;
    try { return LocalDate.parse(value); }
    catch (RuntimeException ex) { throw new InvalidMessageException(field + " must be ISO date", ex); }
  }

  private static final class ProjectionDependencyMissing extends RuntimeException {
    ProjectionDependencyMissing(String message) { super(message); }
  }
}
