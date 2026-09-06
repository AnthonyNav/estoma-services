package com.estoma.platformbff.application.projection;

import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.security.RevocationProjectionService;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The application-side trust boundary for owner Events.
 *
 * The registry is intentionally explicit. A routing key is not authorization: producer,
 * type and version must all match a registered contract before Inbox or a read model changes.
 */
@Service
public class ProjectionEventRuntime {
  private static final String CONSUMER = "bff-projection-runtime";
  private final Map<EventKey, Contract> registry;
  private final InboxRepository inbox;
  private final ProjectionEventProjector projector;

  public enum DispatchOutcome { APPLIED, UNSUPPORTED, UNTRUSTED }

  public ProjectionEventRuntime(InboxRepository inbox, TypedProjectionProjector projector) {
    this.inbox = inbox;
    this.projector = projector;
    this.registry = buildRegistry();
  }

  @Transactional
  public DispatchOutcome dispatch(ProjectionEvent event) {
    Contract contract = event == null ? null
        : registry.get(new EventKey(event.producer(), event.messageType(), event.messageVersion()));
    if (contract == null) return DispatchOutcome.UNTRUSTED;
    try {
      validate(event, contract);
    } catch (IllegalArgumentException invalid) {
      return DispatchOutcome.UNTRUSTED;
    }

    Instant receivedAt = Instant.now();
    boolean firstDelivery = inbox.recordIfNew(new InboxMessage(
        CONSUMER, event.messageId(), "EVENT", event.messageType(), event.messageVersion(),
        event.producer(), event.correlationId(), event.causationId(), null, event.aggregateId(),
        event.aggregateVersion(), receivedAt, receivedAt));
    if (!firstDelivery) return DispatchOutcome.APPLIED;

    // Any database error from this call escapes the transaction. The listener then NACKs/requeues.
    projector.project(event);
    return DispatchOutcome.APPLIED;
  }

  private static void validate(ProjectionEvent event, Contract contract) {
    if (event.messageId() == null || event.messageType() == null || event.messageType().isBlank()
        || event.producer() == null || event.producer().isBlank() || event.messageVersion() <= 0
        || event.occurredAt() == null || event.payload() == null) {
      throw new IllegalArgumentException("invalid projection envelope");
    }
    if (contract.aggregateIdRequired() && event.aggregateId() == null) {
      throw new IllegalArgumentException("projection aggregateId is required");
    }
    if (contract.aggregateVersionRequired()
        && (event.aggregateVersion() == null || event.aggregateVersion() <= 0)) {
      throw new IllegalArgumentException("projection aggregateVersion is required");
    }
    if (!event.payload().keySet().containsAll(contract.requiredFields())
        || !contract.allowedFields().containsAll(event.payload().keySet())) {
      throw new IllegalArgumentException("projection payload shape is not registered");
    }
    if ("appointments".equals(event.producer())
        && "AppointmentScheduled".equals(event.messageType())
        && event.payload().containsKey("qrRepresentation")) {
      Object value = event.payload().get("qrRepresentation");
      if (!(value instanceof String representation)
          || representation.isEmpty() || representation.codePointCount(0, representation.length()) > 2000) {
        throw new IllegalArgumentException("qrRepresentation must contain 1..2000 characters");
      }
    }
  }

  private static Map<EventKey, Contract> buildRegistry() {
    Map<EventKey, Contract> entries = new HashMap<>();
    // Users
    register(entries, "users", "PersonCreated", Set.of("personId", "firstName", "paternalSurname", "institutionalEmail"),
        Set.of("personId", "firstName", "paternalSurname", "maternalSurname", "institutionalEmail", "phone", "status", "version", "createdAt"), true);
    register(entries, "users", "PersonUpdated", Set.of("personId", "firstName", "paternalSurname", "institutionalEmail"),
        Set.of("personId", "firstName", "paternalSurname", "maternalSurname", "institutionalEmail", "phone", "status", "version", "updatedAt"), true);
    register(entries, "users", "PersonActivated", Set.of("personId", "status"),
        Set.of("personId", "status", "version", "activatedAt"), true);
    register(entries, "users", "PersonDeactivated", Set.of("personId", "status", "deactivatedAt", "reasonCode"),
        Set.of("personId", "status", "version", "deactivatedAt", "reasonCode", "reasonDetail"), true);

    // Existing authentication security Events remain in the same projection runtime.
    register(entries, "authentication", "AccountCreated", Set.of("accountId", "personId", "createdAt"),
        Set.of("accountId", "personId", "createdAt"), true);
    register(entries, "authentication", "AccountPresentationUpdated",
        Set.of("accountId", "personId", "loginType", "loginIdentifier", "status", "updatedAt"),
        Set.of("accountId", "personId", "loginType", "loginIdentifier", "status", "updatedAt"), true);
    register(entries, "authentication", "SessionRevoked",
        Set.of("sessionId", "accountId", "revokedAt", "reasonCode"),
        Set.of("sessionId", "accountId", "revokedAt", "reasonCode"), false);
    register(entries, "authentication", "AllAccountSessionsRevoked", Set.of("accountId", "revokedAt", "reasonCode"),
        Set.of("accountId", "revokedAt", "reasonCode"), false);
    register(entries, "authentication", "OtherAccountSessionsRevoked", Set.of("accountId", "currentSessionId", "revokedAt", "reasonCode"),
        Set.of("accountId", "currentSessionId", "revokedAt", "reasonCode"), false);
    register(entries, "authentication", "AccountDeactivated", Set.of("accountId", "personId", "deactivatedAt", "reasonCode"),
        Set.of("accountId", "personId", "deactivatedAt", "reasonCode"), true);

    // Authorization. Role and access versions are fenced independently by their owner streams.
    register(entries, "authorization", "AccountRoleAssigned", Set.of("accountId", "roleCode", "assignedAt"),
        Set.of("accountId", "roleId", "roleCode", "assignedAt"), true);
    register(entries, "authorization", "AccountRoleChanged", Set.of("accountId", "newRoleCode", "changedAt"),
        Set.of("accountId", "previousRoleId", "previousRoleCode", "newRoleId", "newRoleCode", "changedAt"), true);
    for (String type : Set.of("SystemAccessGranted", "SystemAccessSuspended", "SystemAccessRevoked", "SystemAccessRestored")) {
      Set<String> required = type.equals("SystemAccessGranted")
          ? Set.of("systemAccessId", "accountId", "systemId", "systemCode", "status", "grantedAt")
          : Set.of("systemAccessId", "accountId", "systemId", "systemCode", "status");
      register(entries, "authorization", type, required,
          Set.of("systemAccessId", "accountId", "systemId", "systemCode", "roleId", "roleCode", "status",
              "previousStatus", "reasonCode", "reasonDetail", "grantedAt", "suspendedAt", "revokedAt", "restoredAt"), true);
    }

    // Academic
    register(entries, "academic", "StudentAcademicProfileCreated", Set.of("studentAcademicProfileId", "personId", "accountId"),
        Set.of("studentAcademicProfileId", "personId", "accountId", "studentNumber", "currentSemester", "academicStatus", "createdAt"), true);
    for (String type : Set.of("StudentAcademicProfileActivated", "StudentAcademicProfileDeactivated")) {
      register(entries, "academic", type, Set.of("studentAcademicProfileId", "accountId", "academicStatus"),
          Set.of("studentAcademicProfileId", "accountId", "previousStatus", "academicStatus", "timestamp"), true);
    }
    register(entries, "academic", "StudentNumberCorrected", Set.of("studentAcademicProfileId", "accountId"),
        Set.of("studentAcademicProfileId", "accountId", "studentNumber", "newStudentNumber", "previousStudentNumber", "reason", "correctedByAccountId", "correctedAt", "studentNumberVersion"), true);
    register(entries, "academic", "StudentSemesterChanged", Set.of("studentAcademicProfileId", "accountId", "currentSemester"),
        Set.of("studentAcademicProfileId", "accountId", "previousSemester", "currentSemester", "changedAt"), true);
    register(entries, "academic", "StudentAcademicEligibilityChanged", Set.of("eligibilityId", "studentAcademicProfileId", "accountId", "systemCode", "eligible", "policyVersion", "eligibilityVersion", "academicProfileVersion", "evaluatedAt"),
        Set.of("eligibilityId", "studentAcademicProfileId", "accountId", "systemCode", "eligible", "policyVersion", "eligibilityVersion", "academicProfileVersion", "evaluatedAt"), true);
    for (String type : Set.of("AcademicPeriodActivated", "AcademicPeriodClosed", "AcademicPeriodDataStatusChanged")) {
      register(entries, "academic", type, Set.of("academicPeriodId"),
          Set.of("academicPeriodId", "code", "name", "startDate", "endDate", "status", "academicDataStatus", "previousAcademicDataStatus", "reason", "changedAt", "activatedByAccountId", "activatedAt", "closedByActivationOfAcademicPeriodId", "closedAt", "warningAcknowledged", "occurredAt"), true);
    }
    for (String type : Set.of("CourseSectionRegistered", "CourseSectionUpdated", "CourseSectionStatusChanged")) {
      register(entries, "academic", type, Set.of("courseSectionId", "academicPeriodId"),
          Set.of("courseSectionId", "academicPeriodId", "nrc", "name", "status", "previousStatus", "currentStatus", "changedByAccountId", "reason", "registeredAt", "updatedAt", "changedAt"), true);
    }
    for (String type : Set.of("StudentEnrolledInCourseSection", "StudentEnrollmentReactivated", "StudentRemovedFromCourseSection")) {
      register(entries, "academic", type, Set.of("studentAcademicProfileId", "accountId", "courseSectionId", "academicPeriodId"),
          Set.of("studentAcademicProfileId", "accountId", "courseSectionId", "academicPeriodId", "nrc", "status", "enrolledAt", "reactivatedAt", "removedAt", "reason"), true);
    }
    for (String type : Set.of("StudentImportStarted", "StudentImportValidated", "StudentImportRejected", "StudentImportApplicationStarted",
        "StudentImportApplicationResumed", "StudentImportApplicationFailed", "StudentImportProgressUpdated", "StudentImportCompleted")) {
      register(entries, "academic", type, Set.of("importId"),
          Set.of("importId", "academicPeriodId", "status", "startedByAccountId", "authorizedByAccountId", "startedAt", "validatedAt",
              "applicationStartedAt", "completedAt", "failedAt", "summary", "applicationProgress", "validationErrorCount", "executionGeneration",
              "lastResumedByAccountId", "lastResumedAt", "failedStep", "failureReason", "recoverable", "processVersion", "errorCount", "rejectedAt", "importExecution",
              "importMode", "fileReference", "fileChecksum", "phase", "completedItems", "totalItems", "failedItems", "updatedAt",
              "studentsCreated", "studentsUpdated", "studentsReactivated", "studentsDeactivated", "courseSectionsCreated", "courseSectionsActivated", "courseSectionsDeactivated",
              "enrollmentsRegistered", "enrollmentsReactivated", "enrollmentsRemoved", "previousStatus", "resumedByAccountId", "resumedAt", "reason", "occurredAt"), true);
    }
    register(entries, "academic", "StudentImportValidationErrorsAvailable", Set.of("importId", "batchNumber", "batchCount", "errors"),
        Set.of("importId", "academicPeriodId", "batchNumber", "batchCount", "errorCount", "errors", "processVersion", "availableAt"), true);

    // Communications. title/body are accepted only into the typed read model.
    register(entries, "communications", "InboxMessageCreated", Set.of("inboxMessageId", "communicationId", "recipientAccountId", "systemCode", "communicationType", "title", "body", "createdAt"),
        Set.of("inboxMessageId", "communicationId", "recipientAccountId", "systemCode", "communicationType", "title", "body", "createdAt", "expiresAt"), true);
    register(entries, "communications", "InboxMessageExpired", Set.of("inboxMessageId", "expiredAt"),
        Set.of("inboxMessageId", "communicationId", "recipientAccountId", "systemCode", "expiredAt"), false);
    register(entries, "communications", "InboxVisited", Set.of("accountId", "systemCode", "visitedAt"),
        Set.of("accountId", "systemCode", "visitedAt"), false);

    registerWashAdministration(entries);
    registerAppointments(entries);
    registerWashExecution(entries);
    registerJornadas(entries);
    return Map.copyOf(entries);
  }

  private static void registerJornadas(Map<EventKey, Contract> entries) {
    register(entries, "jornadas", "JornadaPublicada",
        Set.of("jornadaId", "tipoJornadaId", "nombre", "fecha", "horaInicio", "horaFin", "lugar", "cupoTotal", "semestresPermitidos"),
        Set.of("jornadaId", "tipoJornadaId", "nombre", "fecha", "horaInicio", "horaFin", "lugar", "cupoTotal", "fechaLimiteDocumentos", "descripcion", "semestresPermitidos"), true);
    register(entries, "jornadas", "JornadaEditada",
        Set.of("jornadaId", "tipoJornadaId", "nombre", "fecha", "horaInicio", "horaFin", "lugar", "cupoTotal", "fechaLimiteDocumentos", "camposModificados"),
        Set.of("jornadaId", "tipoJornadaId", "nombre", "fecha", "horaInicio", "horaFin", "lugar", "cupoTotal", "fechaLimiteDocumentos", "descripcion", "camposModificados"), true);
    register(entries, "jornadas", "JornadaCancelada", Set.of("jornadaId", "motivo"),
        Set.of("jornadaId", "motivo"), true);
    register(entries, "jornadas", "JornadaFinalizada", Set.of("jornadaId"),
        Set.of("jornadaId"), true);
  }

  private static void registerWashAdministration(Map<EventKey, Contract> entries) {
    for (String type : Set.of("ServiceCalendarCreated", "ServiceCalendarUpdated", "ServiceCalendarActivated", "ServiceCalendarDeactivated")) {
      register(entries, "wash-administration", type, Set.of("calendarId"),
          Set.of("calendarId", "code", "name", "timezone", "effectiveFrom", "effectiveUntil", "status", "version", "occurredAt"), true);
    }
    for (String type : Set.of("ServiceScheduleConfigured", "ServiceScheduleUpdated", "ServiceScheduleDeactivated")) {
      register(entries, "wash-administration", type, Set.of("scheduleId"),
          Set.of("scheduleId", "calendarId", "dayOfWeek", "openingTime", "closingTime", "slotDurationMinutes", "status", "version", "occurredAt"), true);
    }
    for (String type : Set.of("AdministrativeClosureScheduled", "AdministrativeClosureCancelled")) {
      register(entries, "wash-administration", type, Set.of("closureId"),
          Set.of("closureId", "calendarId", "scope", "cabinId", "tankId", "startsAt", "endsAt", "reason", "status", "cancelledAt", "occurredAt"), true);
    }
    for (String type : Set.of("CabinRegistered", "CabinUpdated", "CabinActivated", "CabinDeactivated", "CabinRetired")) {
      register(entries, "wash-administration", type, Set.of("cabinId"),
          Set.of("cabinId", "code", "name", "cabinType", "status", "occurredAt"), true);
    }
    for (String type : Set.of("TankRegistered", "TankUpdated", "TankActivated", "TankDeactivated", "TankRetired")) {
      register(entries, "wash-administration", type, Set.of("tankId"),
          Set.of("tankId", "cabinId", "code", "name", "configuredCapacity", "status", "occurredAt"), true);
    }
  }

  private static void registerAppointments(Map<EventKey, Contract> entries) {
    register(entries, "appointments", "AppointmentTimeSlotCreated", Set.of("appointmentTimeSlotId", "calendarId", "sourceScheduleId", "serviceDate", "startsAt", "endsAt", "timezone"),
        Set.of("appointmentTimeSlotId", "calendarId", "sourceScheduleId", "serviceDate", "startsAt", "endsAt", "timezone", "bookingDeadlineAt", "cancellationDeadlineAt", "arrivalToleranceUntil", "status", "version"), true);
    for (String type : Set.of("AppointmentTimeSlotCancelled")) {
      register(entries, "appointments", type, Set.of("appointmentTimeSlotId"),
          Set.of("appointmentTimeSlotId", "calendarId", "serviceDate", "startsAt", "endsAt", "reason", "version"), true);
    }
    for (String type : Set.of("AppointmentTimeSlotCapacityRecalculated", "AppointmentTimeSlotCapacityReduced")) {
      register(entries, "appointments", type, Set.of("appointmentTimeSlotId", "projectionVersion"),
          Set.of("appointmentTimeSlotId", "capacityNormal", "capacityJourney", "capacityImmunocompromised", "previousCapacityNormal", "previousCapacityJourney", "previousCapacityImmunocompromised", "affectedAppointmentCount", "projectionVersion"), true);
    }
    Set<String> appointmentFields = Set.of("appointmentId", "appointmentTimeSlotId", "studentAccountId",
        "appointmentType", "courseSectionId", "instrumentCount", "pieceType",
        "exceptionalAuthorizationId", "createdAt", "cancellationType", "cancellationSubreason",
        "cancellationReason", "reason", "cancelledByAccountId", "cancelledAt", "washExecutionId",
        "sourceEventId", "missedAt", "rejectionReason", "rejectedByAccountId", "rejectedAt",
        "startedAt", "completedAt", "completedByAccountId", "status", "version");
    register(entries, "appointments", "AppointmentScheduled",
        Set.of("appointmentId", "appointmentTimeSlotId", "studentAccountId"),
        with(appointmentFields, "qrRepresentation"), true);
    for (String type : Set.of("AppointmentCancelled", "AppointmentMissed", "AppointmentEntryRejected",
        "AppointmentStarted", "AppointmentCompleted")) {
      register(entries, "appointments", type, Set.of("appointmentId", "appointmentTimeSlotId", "studentAccountId"),
          appointmentFields, true);
    }
    register(entries, "appointments", "ExceptionalAppointmentAuthorizationGranted", Set.of("authorizationId", "studentAccountId", "authorizationDate", "grantedAt"),
        Set.of("authorizationId", "studentAccountId", "authorizationDate", "authorizedByAccountId", "reason", "grantedAt"), true);
    register(entries, "appointments", "ExceptionalAppointmentAuthorizationCancelled", Set.of("authorizationId", "studentAccountId", "authorizationDate", "cancelledAt"),
        Set.of("authorizationId", "studentAccountId", "authorizationDate", "cancellationReason", "cancelledByAccountId", "cancelledAt"), true);
  }

  private static void registerWashExecution(Map<EventKey, Contract> entries) {
    for (String type : Set.of("WashArrivalRegistered", "WashEntryAuthorized", "WashEntryRejected", "WashExecutionStarted",
        "WashExecutionPendingReassignment", "WashExecutionCompleted", "WashExecutionCancelled", "WashExitSubmitted")) {
      register(entries, "wash-execution", type, Set.of("washExecutionId"),
          Set.of("washExecutionId", "appointmentId", "appointmentTimeSlotId", "executionStatus", "appointmentType", "resourceAssignmentId", "cabinId", "tankId",
              "executionVersion", "entryValidationId", "identityConfirmed", "requirementsSatisfied", "decidedByAccountId", "decidedAt",
              "arrivedAt", "authorizedAt", "startedAt", "submittedAt", "completedAt", "cancelledAt", "cancellationType", "cancellationSubreason", "cancellationReason", "rejectionReason", "hadActiveAssignment", "reason",
              "cancelledByAccountId", "completedByAccountId", "submittedByStudentAccountId", "packageCount", "greenPaperCassette8Count", "greenPaperCassette10Count", "witnessTapePortionCount",
              "finalPackageCount", "finalGreenPaperCassette8Count", "finalGreenPaperCassette10Count", "finalWitnessTapePortionCount"), true);
    }
    register(entries, "wash-execution", "WashResourceAssigned",
        Set.of("resourceAssignmentId", "washExecutionId", "cabinId", "tankId", "assignmentType", "assignedAt"),
        Set.of("resourceAssignmentId", "washExecutionId", "appointmentId", "appointmentTimeSlotId", "cabinId", "tankId", "assignmentType", "assignedByAccountId", "assignedAt"), true);
    register(entries, "wash-execution", "WashResourceReassigned",
        Set.of("washExecutionId", "previousResourceAssignmentId", "previousCabinId", "previousTankId", "releasedAt",
            "newResourceAssignmentId", "newCabinId", "newTankId", "assignmentType", "assignedAt"),
        Set.of("washExecutionId", "appointmentId", "appointmentTimeSlotId", "previousResourceAssignmentId", "previousCabinId", "previousTankId",
            "releaseReason", "releasedAt", "newResourceAssignmentId", "newCabinId", "newTankId", "assignmentType", "assignedByAccountId", "assignedAt"), true);
    for (String type : Set.of("OperationalResourceDisabled", "OperationalResourceRestored")) {
      register(entries, "wash-execution", type, Set.of("resourceUnavailabilityId"),
          Set.of("resourceUnavailabilityId", "cabinId", "tankId", "detectedDuringWashExecutionId", "causeType", "reason", "status", "disabledByAccountId", "disabledAt", "restoredByAccountId", "restoredAt", "resolution", "resourceUnavailabilityVersion"), true);
    }
    for (String type : Set.of("CleaningPeriodStarted", "CleaningPeriodCompleted")) {
      register(entries, "wash-execution", type, Set.of("cleaningPeriodId", "washExecutionId"),
          Set.of("cleaningPeriodId", "washExecutionId", "appointmentId", "resourceAssignmentId", "cabinId", "tankId", "cleaningTrigger", "startsAt", "expectedEndsAt", "completedAt", "cleaningPeriodVersion"), true);
    }
  }

  private static void register(Map<EventKey, Contract> entries, String producer, String type,
      Set<String> required, Set<String> allowed, boolean aggregateRequired) {
    entries.put(new EventKey(producer, type, 1),
        new Contract(Set.copyOf(required), Set.copyOf(allowed), aggregateRequired, aggregateRequired));
  }

  private static Set<String> with(Set<String> fields, String additionalField) {
    Set<String> result = new HashSet<>(fields);
    result.add(additionalField);
    return Set.copyOf(result);
  }

  public record EventKey(String producer, String messageType, int messageVersion) {}

  private record Contract(
      Set<String> requiredFields,
      Set<String> allowedFields,
      boolean aggregateIdRequired,
      boolean aggregateVersionRequired) {}
}
