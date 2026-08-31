package com.estoma.platformbff.application.projection;

import static com.estoma.platformbff.application.projection.ProjectionPayloads.*;

import com.estoma.platformbff.application.port.ProjectionReadModelPort;
import com.estoma.platformbff.application.security.RevocationProjectionEvent;
import com.estoma.platformbff.application.security.RevocationProjectionEvent.AccountSessionsRevoked;
import com.estoma.platformbff.application.security.RevocationProjectionEvent.CutoffKind;
import com.estoma.platformbff.application.security.RevocationProjectionEvent.Metadata;
import com.estoma.platformbff.application.security.RevocationProjectionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Maps owner Events to typed records; this class deliberately has no transport dependencies. */
@Component
public class TypedProjectionProjector implements ProjectionEventProjector {
  private final ProjectionReadModelPort readModels;
  private final RevocationProjectionService revocations;

  public TypedProjectionProjector(
      ProjectionReadModelPort readModels, RevocationProjectionService revocations) {
    this.readModels = readModels;
    this.revocations = revocations;
  }

  @Override
  public void project(ProjectionEvent event) {
    switch (event.producer()) {
      case "users" -> projectUsers(event);
      case "authorization" -> projectAuthorization(event);
      case "academic" -> projectAcademic(event);
      case "communications" -> projectCommunications(event);
      case "wash-administration" -> projectWashAdministration(event);
      case "appointments" -> projectAppointments(event);
      case "wash-execution" -> projectWashExecution(event);
      case "authentication" -> projectAuthentication(event);
      case "jornadas" -> projectJornadas(event);
      default -> throw new IllegalArgumentException("projection producer is not registered");
    }
  }

  private void projectUsers(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    UUID personId = uuid(p, "personId");
    aggregate(event, personId);
    long version = aggregateVersion(event);
    String type = event.messageType();
    if (type.equals("PersonCreated") || type.equals("PersonUpdated")) {
      readModels.upsert(new ProjectionReadModel.Person(
          personId, text(p, "firstName"), text(p, "paternalSurname"),
          optionalText(p, "maternalSurname"), null,
          optionalText(p, "status"), version, event.occurredAt()));
    } else {
      readModels.upsert(new ProjectionReadModel.Person(
          personId, null, null, null, null, text(p, "status"), version, event.occurredAt()));
    }
    if (type.equals("PersonDeactivated")) {
      projectPersonRevocation(event, personId, p);
    }
  }

  private void projectAuthorization(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    long version = aggregateVersion(event);
    switch (event.messageType()) {
      case "AccountRoleAssigned" -> {
        UUID accountId = uuid(p, "accountId");
        aggregate(event, accountId);
        readModels.upsert(new ProjectionReadModel.AuthorizationRole(
            accountId, text(p, "roleCode"), version, event.occurredAt()));
      }
      case "AccountRoleChanged" -> {
        UUID accountId = uuid(p, "accountId");
        aggregate(event, accountId);
        readModels.upsert(new ProjectionReadModel.AuthorizationRole(
            accountId, text(p, "newRoleCode"), version, event.occurredAt()));
      }
      case "SystemAccessGranted", "SystemAccessSuspended", "SystemAccessRevoked",
          "SystemAccessRestored" -> {
        UUID accessId = uuid(p, "systemAccessId");
        aggregate(event, accessId);
        readModels.upsert(new ProjectionReadModel.AuthorizationAccess(
            accessId, uuid(p, "accountId"), uuid(p, "systemId"), text(p, "systemCode"),
            text(p, "status"), version, event.occurredAt()));
      }
      default -> throw new IllegalArgumentException("authorization Event is not registered");
    }
  }

  private void projectAcademic(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    long version = aggregateVersion(event);
    switch (event.messageType()) {
      case "AcademicPeriodActivated", "AcademicPeriodClosed", "AcademicPeriodDataStatusChanged" -> {
        UUID id = uuid(p, "academicPeriodId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.AcademicPeriod(
            id, optionalText(p, "code"), optionalText(p, "name"), optionalDate(p, "startDate"),
            optionalDate(p, "endDate"), optionalText(p, "status"),
            optionalText(p, "academicDataStatus"), version, event.occurredAt()));
      }
      case "StudentAcademicProfileCreated", "StudentAcademicProfileActivated",
          "StudentAcademicProfileDeactivated", "StudentNumberCorrected", "StudentSemesterChanged" -> {
        UUID id = uuid(p, "studentAcademicProfileId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.StudentProfile(
            id, optionalUuid(p, "personId"), uuid(p, "accountId"), studentNumber(p),
            optionalInteger(p, "currentSemester"), optionalText(p, "academicStatus"), version,
            event.occurredAt()));
      }
      case "StudentAcademicEligibilityChanged" -> {
        UUID id = uuid(p, "eligibilityId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.StudentEligibility(
            id, uuid(p, "studentAcademicProfileId"), text(p, "systemCode"), bool(p, "eligible"),
            optionalText(p, "policyVersion"), number(p, "eligibilityVersion"),
            number(p, "academicProfileVersion"), instant(p, "evaluatedAt"), event.occurredAt()));
      }
      case "CourseSectionRegistered", "CourseSectionUpdated", "CourseSectionStatusChanged" -> {
        UUID id = uuid(p, "courseSectionId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.CourseSection(
            id, uuid(p, "academicPeriodId"), optionalText(p, "nrc"), optionalText(p, "name"),
            courseSectionStatus(p), version, event.occurredAt()));
      }
      case "StudentEnrolledInCourseSection", "StudentEnrollmentReactivated",
          "StudentRemovedFromCourseSection" -> {
        UUID id = uuid(p, "studentAcademicProfileId");
        readModels.upsert(new ProjectionReadModel.Enrollment(
            id, uuid(p, "accountId"), uuid(p, "courseSectionId"), uuid(p, "academicPeriodId"),
            optionalText(p, "nrc"), enrollmentStatus(event.messageType(), p), version,
            event.occurredAt()));
      }
      default -> projectImport(event, p, version);
    }
  }

  private void projectImport(ProjectionEvent event, Map<String, Object> p, long version) {
    if (!event.messageType().startsWith("StudentImport")) {
      throw new IllegalArgumentException("academic Event is not registered");
    }
    UUID id = uuid(p, "importId");
    aggregate(event, id);
    if (event.messageType().equals("StudentImportValidationErrorsAvailable")) {
      projectValidationErrors(event, id, p, version);
      return;
    }
    readModels.upsert(new ProjectionReadModel.StudentImport(
        id, optionalUuid(p, "academicPeriodId"), importStatus(p),
        firstUuid(p, "startedByAccountId", "resumedByAccountId"), optionalUuid(p, "authorizedByAccountId"),
        optionalInstant(p, "startedAt"), optionalInstant(p, "validatedAt"),
        optionalInstant(p, "applicationStartedAt"), optionalInstant(p, "completedAt"),
        optionalInstant(p, "failedAt"), optionalText(p, "summary"),
        optionalText(p, "applicationProgress"), (int) firstNumber(p, "validationErrorCount", "errorCount", 0),
        optionalNumber(p, "executionGeneration", 0), firstUuid(p, "lastResumedByAccountId", "resumedByAccountId"),
        firstInstant(p, "lastResumedAt", "resumedAt"), optionalText(p, "failedStep"),
        firstText(p, "failureReason", "reason"), Boolean.TRUE.equals(optionalBool(p, "recoverable")),
        optionalNumber(p, "processVersion", version), event.occurredAt()));
  }

  @SuppressWarnings("unchecked")
  private void projectValidationErrors(
      ProjectionEvent event, UUID importId, Map<String, Object> p, long version) {
    int batch = integer(p, "batchNumber");
    int batches = integer(p, "batchCount");
    Object errors = required(p, "errors");
    if (!(errors instanceof List<?> list)) {
      throw new IllegalArgumentException("validation errors must be an array");
    }
    long processVersion = optionalNumber(p, "processVersion", version);
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> raw)) {
        throw new IllegalArgumentException("validation error must be an object");
      }
      Map<String, Object> error = (Map<String, Object>) raw;
      readModels.upsert(new ProjectionReadModel.ValidationError(
          importId, number(error, "sequence"), optionalText(error, "sheet"), optionalNumber(error, "row", 0),
          optionalText(error, "column"), text(error, "errorCode"), text(error, "message"), batch,
          batches, processVersion, event.occurredAt()));
    }
  }

  private void projectCommunications(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    switch (event.messageType()) {
      case "InboxMessageCreated" -> {
        UUID id = uuid(p, "inboxMessageId");
        UUID communicationId = uuid(p, "communicationId");
        // The owner envelope, when present, belongs to Communication; it is not an InboxMessage version.
        aggregate(event, communicationId);
        readModels.upsert(new ProjectionReadModel.MailboxMessage(
            id, communicationId, uuid(p, "recipientAccountId"), text(p, "systemCode"),
            text(p, "communicationType"), text(p, "title"), text(p, "body"), instant(p, "createdAt"),
            optionalInstant(p, "expiresAt"), null, event.occurredAt()));
      }
      case "InboxMessageExpired" -> {
        UUID id = uuid(p, "inboxMessageId");
        readModels.upsert(new ProjectionReadModel.MailboxMessage(
            id, null, null, null, null, null, null, null, null, instant(p, "expiredAt"),
            event.occurredAt()));
      }
      case "InboxVisited" -> {
        UUID accountId = uuid(p, "accountId");
        readModels.upsert(new ProjectionReadModel.MailboxVisit(
            accountId, text(p, "systemCode"), instant(p, "visitedAt"), event.occurredAt()));
      }
      default -> throw new IllegalArgumentException("communications Event is not registered");
    }
  }

  private void projectWashAdministration(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    long version = aggregateVersion(event);
    switch (event.messageType()) {
      case "ServiceCalendarCreated", "ServiceCalendarUpdated", "ServiceCalendarActivated",
          "ServiceCalendarDeactivated" -> {
        UUID id = uuid(p, "calendarId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.WashCalendar(
            id, optionalText(p, "code"), optionalText(p, "name"), optionalText(p, "timezone"),
            optionalDate(p, "effectiveFrom"), optionalDate(p, "effectiveUntil"),
            optionalText(p, "status"), version, event.occurredAt()));
      }
      case "ServiceScheduleConfigured", "ServiceScheduleUpdated", "ServiceScheduleDeactivated" -> {
        UUID id = uuid(p, "scheduleId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.WashSchedule(
            id, optionalUuid(p, "calendarId"), optionalInteger(p, "dayOfWeek") == null ? 0 : integer(p, "dayOfWeek"),
            optionalTime(p, "openingTime"), optionalTime(p, "closingTime"),
            optionalInteger(p, "slotDurationMinutes") == null ? 0 : integer(p, "slotDurationMinutes"),
            optionalText(p, "status"), version, event.occurredAt()));
      }
      case "AdministrativeClosureScheduled", "AdministrativeClosureCancelled" -> {
        UUID id = uuid(p, "closureId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.AdministrativeClosure(
            id, optionalUuid(p, "calendarId"), optionalText(p, "scope"), optionalUuid(p, "cabinId"),
            optionalUuid(p, "tankId"), optionalInstant(p, "startsAt"), optionalInstant(p, "endsAt"),
            optionalText(p, "reason"), optionalText(p, "status"), version, event.occurredAt()));
      }
      case "CabinRegistered", "CabinUpdated", "CabinActivated", "CabinDeactivated", "CabinRetired" -> {
        UUID id = uuid(p, "cabinId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.Cabin(
            id, optionalText(p, "code"), optionalText(p, "name"), optionalText(p, "cabinType"),
            optionalText(p, "status"), version, event.occurredAt()));
      }
      case "TankRegistered", "TankUpdated", "TankActivated", "TankDeactivated", "TankRetired" -> {
        UUID id = uuid(p, "tankId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.Tank(
            id, optionalUuid(p, "cabinId"), optionalText(p, "code"), optionalText(p, "name"),
            optionalInteger(p, "configuredCapacity") == null ? 0 : integer(p, "configuredCapacity"),
            optionalText(p, "status"), version, event.occurredAt()));
      }
      default -> throw new IllegalArgumentException("wash administration Event is not registered");
    }
  }

  private void projectAppointments(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    long version = aggregateVersion(event);
    switch (event.messageType()) {
      case "AppointmentTimeSlotCreated", "AppointmentTimeSlotCancelled", "AppointmentTimeSlotCapacityRecalculated",
          "AppointmentTimeSlotCapacityReduced" -> {
        UUID id = event.aggregateId();
        if (id == null) throw new IllegalArgumentException("slot aggregateId is required");
        if (p.containsKey("appointmentTimeSlotId")) aggregate(event, uuid(p, "appointmentTimeSlotId"));
        long capacityVersion = p.containsKey("projectionVersion") ? number(p, "projectionVersion") : 0;
        readModels.upsert(new ProjectionReadModel.AppointmentSlot(
            id, optionalUuid(p, "calendarId"), optionalUuid(p, "sourceScheduleId"), optionalDate(p, "serviceDate"),
            optionalInstant(p, "startsAt"), optionalInstant(p, "endsAt"), optionalText(p, "timezone"),
            optionalInstant(p, "bookingDeadlineAt"), optionalInstant(p, "cancellationDeadlineAt"),
            optionalInstant(p, "arrivalToleranceUntil"), optionalText(p, "status"),
            optionalInteger(p, "capacityNormal"), optionalInteger(p, "capacityJourney"),
            optionalInteger(p, "capacityImmunocompromised"),
            event.messageType().startsWith("AppointmentTimeSlotCapacity") ? 0 : version,
            capacityVersion, event.occurredAt()));
      }
      case "AppointmentScheduled", "AppointmentCancelled", "AppointmentMissed", "AppointmentEntryRejected",
          "AppointmentStarted", "AppointmentCompleted" -> {
        UUID id = uuid(p, "appointmentId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.Appointment(
            id, appointmentSlotId(p), uuid(p, "studentAccountId"), optionalText(p, "appointmentType"),
            optionalUuid(p, "courseSectionId"), optionalInteger(p, "instrumentCount"), optionalText(p, "pieceType"),
            appointmentStatus(event.messageType(), p), optionalText(p, "cancellationType"), version,
            event.occurredAt()));
      }
      case "ExceptionalAppointmentAuthorizationGranted", "ExceptionalAppointmentAuthorizationCancelled" -> {
        UUID id = uuid(p, "authorizationId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.ExceptionalAuthorization(
            id, uuid(p, "studentAccountId"), optionalDate(p, "authorizationDate"),
            event.messageType().endsWith("Granted") ? "AUTHORIZED" : "CANCELLED",
            optionalUuid(p, "authorizedByAccountId"), optionalText(p, "reason"), version, event.occurredAt()));
      }
      default -> throw new IllegalArgumentException("appointments Event is not registered");
    }
  }

  private void projectWashExecution(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    long version = aggregateVersion(event);
    switch (event.messageType()) {
      case "WashResourceAssigned" -> {
        UUID executionId = uuid(p, "washExecutionId");
        aggregate(event, executionId);
        readModels.upsert(new ProjectionReadModel.ResourceAssignment(
            uuid(p, "resourceAssignmentId"), executionId, optionalUuid(p, "appointmentId"),
            optionalUuid(p, "appointmentTimeSlotId"), optionalUuid(p, "cabinId"), optionalUuid(p, "tankId"),
            optionalText(p, "assignmentType"), optionalInstant(p, "assignedAt"), null,
            event.messageId(), version, event.occurredAt()));
      }
      case "WashResourceReassigned" -> {
        UUID executionId = uuid(p, "washExecutionId");
        aggregate(event, executionId);

        readModels.upsert(new ProjectionReadModel.ResourceAssignment(
            uuid(p, "previousResourceAssignmentId"), executionId, optionalUuid(p, "appointmentId"),
            optionalUuid(p, "appointmentTimeSlotId"), optionalUuid(p, "previousCabinId"),
            optionalUuid(p, "previousTankId"), null, null, optionalInstant(p, "releasedAt"),
            event.messageId(), version, event.occurredAt()));

        readModels.upsert(new ProjectionReadModel.ResourceAssignment(
            uuid(p, "newResourceAssignmentId"), executionId, optionalUuid(p, "appointmentId"),
            optionalUuid(p, "appointmentTimeSlotId"), optionalUuid(p, "newCabinId"), optionalUuid(p, "newTankId"),
            optionalText(p, "assignmentType"), optionalInstant(p, "assignedAt"), null,
            event.messageId(), version, event.occurredAt()));
      }
      case "OperationalResourceDisabled", "OperationalResourceRestored" -> {
        UUID id = uuid(p, "resourceUnavailabilityId");
        aggregate(event, id);
        UUID cabin = optionalUuid(p, "cabinId");
        UUID tank = optionalUuid(p, "tankId");
        readModels.upsert(new ProjectionReadModel.ResourceUnavailability(
            id, cabin != null ? "CABIN" : "TANK", cabin != null ? cabin : tank,
            event.messageType().endsWith("Disabled") ? "DISABLED" : "RESTORED",
            optionalText(p, "reason"), version, event.occurredAt()));
      }
      case "CleaningPeriodStarted", "CleaningPeriodCompleted" -> {
        UUID id = uuid(p, "cleaningPeriodId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.CleaningPeriod(
            id, uuid(p, "washExecutionId"), optionalUuid(p, "resourceAssignmentId"), optionalUuid(p, "cabinId"),
            optionalUuid(p, "tankId"), optionalInstant(p, "startsAt"), optionalInstant(p, "expectedEndsAt"),
            optionalInstant(p, "completedAt"), event.messageType().endsWith("Completed") ? "COMPLETED" : "IN_PROGRESS",
            version, event.occurredAt()));
      }
      default -> {
        UUID id = uuid(p, "washExecutionId");
        aggregate(event, id);
        readModels.upsert(new ProjectionReadModel.WashExecution(
            id, optionalUuid(p, "appointmentId"), optionalText(p, "appointmentType"), executionStatus(event.messageType(), p),
            optionalInstant(p, "arrivedAt"), optionalInstant(p, "authorizedAt"), optionalInstant(p, "startedAt"),
            optionalInstant(p, "submittedAt"), optionalInstant(p, "completedAt"), optionalInstant(p, "cancelledAt"),
            optionalText(p, "cancellationType"), optionalText(p, "rejectionReason"), null, null, null, null,
            version, event.occurredAt()));
      }
    }
  }

  private void projectJornadas(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    long version = aggregateVersion(event);
    UUID id = uuid(p, "jornadaId");
    aggregate(event, id);
    switch (event.messageType()) {
      case "JornadaPublicada", "JornadaEditada" -> readModels.upsert(new ProjectionReadModel.Jornada(
          id, optionalUuid(p, "tipoJornadaId"), optionalText(p, "nombre"), optionalDate(p, "fecha"),
          optionalTime(p, "horaInicio"), optionalTime(p, "horaFin"), optionalText(p, "lugar"),
          optionalInteger(p, "cupoTotal"), optionalDate(p, "fechaLimiteDocumentos"),
          optionalText(p, "descripcion"), "PUBLICADA", version, event.occurredAt()));
      case "JornadaCancelada" -> readModels.upsert(new ProjectionReadModel.Jornada(
          id, null, null, null, null, null, null, null, null, null, "CANCELADA", version,
          event.occurredAt()));
      case "JornadaFinalizada" -> readModels.upsert(new ProjectionReadModel.Jornada(
          id, null, null, null, null, null, null, null, null, null, "FINALIZADA", version,
          event.occurredAt()));
      default -> throw new IllegalArgumentException("jornadas Event is not registered");
    }
  }

  private void projectAuthentication(ProjectionEvent event) {
    Map<String, Object> p = event.payload();
    Metadata metadata = new Metadata(event.messageId(), event.messageType(), event.messageVersion(),
        event.producer(), event.occurredAt(), event.correlationId(), event.causationId(),
        event.aggregateId(), event.aggregateVersion());
    switch (event.messageType()) {
      case "AccountCreated" -> {
        UUID accountId = uuid(p, "accountId");
        aggregate(event, accountId);
        revocations.projectTrusted(new RevocationProjectionEvent.AccountCreated(
            metadata, accountId, uuid(p, "personId"), aggregateVersion(event),
            instant(p, "createdAt")));
      }
      case "SessionRevoked" -> revocations.projectTrusted(new RevocationProjectionEvent.SessionRevoked(
          metadata, uuid(p, "sessionId"), instant(p, "revokedAt").getEpochSecond(), text(p, "reasonCode")));
      case "AllAccountSessionsRevoked", "OtherAccountSessionsRevoked" -> {
        UUID accountId = uuid(p, "accountId");
        if (event.aggregateId() != null) aggregate(event, accountId);
        boolean all = event.messageType().startsWith("All");
        revocations.projectTrusted(new AccountSessionsRevoked(
            metadata, accountId, instant(p, "revokedAt").getEpochSecond(), all ? CutoffKind.ALL : CutoffKind.OTHER,
            all ? null : uuid(p, "currentSessionId"), text(p, "reasonCode")));
      }
      case "AccountDeactivated" -> {
        UUID accountId = uuid(p, "accountId");
        aggregate(event, accountId);
        Instant at = instant(p, "deactivatedAt");
        revocations.projectTrusted(new RevocationProjectionEvent.AccountDeactivated(
            metadata, accountId, uuid(p, "personId"), aggregateVersion(event), at.getEpochSecond(),
            text(p, "reasonCode"), at));
      }
      default -> throw new IllegalArgumentException("authentication Event is not registered");
    }
  }

  private void projectPersonRevocation(ProjectionEvent event, UUID personId, Map<String, Object> p) {
    Metadata metadata = new Metadata(event.messageId(), event.messageType(), event.messageVersion(),
        event.producer(), event.occurredAt(), event.correlationId(), event.causationId(),
        event.aggregateId(), event.aggregateVersion());
    Instant at = instant(p, "deactivatedAt");
    revocations.projectTrusted(new RevocationProjectionEvent.PersonDeactivated(
        metadata, personId, at.getEpochSecond(), text(p, "reasonCode")));
  }

  private static String enrollmentStatus(String type, Map<String, Object> p) {
    return optionalText(p, "status") != null ? optionalText(p, "status")
        : type.equals("StudentRemovedFromCourseSection") ? "REMOVED" : "ENROLLED";
  }

  private static String studentNumber(Map<String, Object> payload) {
    String current = optionalText(payload, "studentNumber");
    return current == null ? optionalText(payload, "newStudentNumber") : current;
  }

  private static String importStatus(Map<String, Object> payload) {
    String status = optionalText(payload, "status");
    return status == null ? optionalText(payload, "phase") : status;
  }

  private static UUID firstUuid(Map<String, Object> payload, String first, String second) {
    UUID value = optionalUuid(payload, first);
    return value == null ? optionalUuid(payload, second) : value;
  }

  private static Instant firstInstant(Map<String, Object> payload, String first, String second) {
    Instant value = optionalInstant(payload, first);
    return value == null ? optionalInstant(payload, second) : value;
  }

  private static String firstText(Map<String, Object> payload, String first, String second) {
    String value = optionalText(payload, first);
    return value == null ? optionalText(payload, second) : value;
  }

  private static long firstNumber(Map<String, Object> payload, String first, String second, long fallback) {
    if (payload.containsKey(first) && payload.get(first) != null) return number(payload, first);
    if (payload.containsKey(second) && payload.get(second) != null) return number(payload, second);
    return fallback;
  }

  private static String courseSectionStatus(Map<String, Object> payload) {
    String current = optionalText(payload, "status");
    return current == null ? optionalText(payload, "currentStatus") : current;
  }

  private static UUID appointmentSlotId(Map<String, Object> payload) {
    UUID value = optionalUuid(payload, "appointmentTimeSlotId");
    return value == null ? uuid(payload, "slotId") : value;
  }

  private static String appointmentStatus(String type, Map<String, Object> p) {
    String status = optionalText(p, "status");
    if (status != null) return status;
    return switch (type) {
      case "AppointmentCancelled" -> "CANCELLED";
      case "AppointmentMissed" -> "MISSED";
      case "AppointmentEntryRejected" -> "ENTRY_REJECTED";
      case "AppointmentStarted" -> "IN_PROGRESS";
      case "AppointmentCompleted" -> "COMPLETED";
      default -> "SCHEDULED";
    };
  }

  private static String executionStatus(String type, Map<String, Object> p) {
    String status = optionalText(p, "executionStatus");
    if (status != null) return status;
    return switch (type) {
      case "WashArrivalRegistered" -> "ARRIVED";
      case "WashEntryAuthorized" -> "AUTHORIZED";
      case "WashEntryRejected" -> "ENTRY_REJECTED";
      case "WashExecutionStarted" -> "STARTED";
      case "WashExecutionPendingReassignment" -> "PENDING_REASSIGNMENT";
      case "WashExecutionCompleted" -> "COMPLETED";
      case "WashExecutionCancelled" -> "CANCELLED";
      default -> "IN_PROGRESS";
    };
  }
}
