package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.port.WashSupervisionQueryPort;
import com.estoma.platformbff.application.port.WashStudentQueryPort;
import com.estoma.platformbff.application.port.AppointmentQrReadPort;
import com.estoma.platformbff.application.projection.AppointmentQrRecoveryService;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.ActiveAssignmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.AppointmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.AppointmentTimeSlotResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.CourseSectionResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.PendingReassignmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.ResourceResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.StudentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.SubmittedExitMaterialsResponse;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.SupervisorLookupRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.LookupType;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.ActiveResourceAssignment;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.AppointmentTimeSlot;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.CourseSectionReference;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.NextAction;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SubmittedExitMaterials;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorAppointment;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorHomeResponse;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorHomeSummary;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorLookupResponse;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorExecution;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorExecutionAppointment;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorExecutionCourseSection;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorExecutionDetailResponse;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorStudent;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorWashExecution;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Service
public class WashSupervisionQueryService {
  private static final ZoneId SERVICE_ZONE = ZoneId.of("America/Mexico_City");
  private static final Set<String> ACTIVE_EXECUTION_STATUSES = Set.of(
      "PENDING_ENTRY", "PENDING_REASSIGNMENT", "IN_PROGRESS", "EXIT_SUBMITTED");
  private static final Set<String> TERMINAL_APPOINTMENT_STATUSES = Set.of(
      "ENTRY_REJECTED", "COMPLETED", "CANCELLED", "MISSED");
  private static final Set<String> TERMINAL_EXECUTION_STATUSES = Set.of(
      "ENTRY_REJECTED", "COMPLETED", "CANCELLED");
  private static final Set<String> EXECUTION_STATUSES = Set.of(
      "PENDING_ENTRY", "ENTRY_REJECTED", "PENDING_REASSIGNMENT", "IN_PROGRESS",
      "EXIT_SUBMITTED", "COMPLETED", "CANCELLED");
  private static final Set<String> APPOINTMENT_STATUSES = Set.of(
      "SCHEDULED", "CANCELLED", "MISSED", "ENTRY_REJECTED", "IN_PROGRESS", "COMPLETED");
  private static final Set<String> APPOINTMENT_TYPES = Set.of(
      "NORMAL", "JOURNEY", "IMMUNOCOMPROMISED");
  private static final Set<String> PIECE_TYPES = Set.of(
      "HIGH_SPEED", "LOW_SPEED", "CONTRA_ANGLE");
  private static final Set<String> ASSIGNMENT_TYPES = Set.of("INITIAL", "REASSIGNMENT");

  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final WashSupervisionQueryPort projections;
  private final WashStudentQueryPort studentProjections;
  private final Clock clock;
  private final AppointmentQrReadPort appointmentQr;
  private final AppointmentQrRecoveryService qrRecovery;
  private final boolean qrEnabled;
  private final boolean directSupervisorCompletionEnabled;

  @Autowired
  public WashSupervisionQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashSupervisionQueryPort projections,
      WashStudentQueryPort studentProjections, Clock clock, AppointmentQrReadPort appointmentQr,
      AppointmentQrRecoveryService qrRecovery,
      @Value("${platform.wash.qr.enabled:false}") boolean qrEnabled,
      @Value("${platform.wash.direct-supervisor-completion-enabled:false}")
      boolean directSupervisorCompletionEnabled) {
    this.authorization = authorization;
    this.readiness = readiness;
    this.projections = projections;
    this.studentProjections = studentProjections;
    this.clock = clock;
    this.appointmentQr = appointmentQr;
    this.qrRecovery = qrRecovery;
    this.qrEnabled = qrEnabled;
    this.directSupervisorCompletionEnabled = directSupervisorCompletionEnabled;
  }

  public WashSupervisionQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashSupervisionQueryPort projections,
      WashStudentQueryPort studentProjections, Clock clock, AppointmentQrReadPort appointmentQr,
      AppointmentQrRecoveryService qrRecovery, boolean qrEnabled) {
    this(authorization, readiness, projections, studentProjections, clock, appointmentQr,
        qrRecovery, qrEnabled, false);
  }

  public WashSupervisionQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashSupervisionQueryPort projections,
      WashStudentQueryPort studentProjections, Clock clock) {
    this(authorization, readiness, projections, studentProjections, clock, null, null, false, false);
  }

  public SupervisorHomeResponse home() {
    authorization.requireWashSupervisor();
    readiness.requireReady("appointments", "wash-execution");
    LocalDate serviceDate = serviceDate();
    var snapshot = projections.summarizeHome(serviceDate);
    return new SupervisorHomeResponse(serviceDate,
        new SupervisorHomeSummary(snapshot.registeredAppointments(), snapshot.inProcessAppointments(),
            snapshot.completedAppointments(), snapshot.deniedAppointments(), snapshot.cancelledAppointments()),
        snapshot.pendingReassignmentsCount());
  }

  public SupervisorLookupResponse lookup(SupervisorLookupRequest request) {
    authorization.requireWashSupervisor();
    readiness.requireReady("person", "academic", "appointments", "wash-execution", "wash-administration");
    LocalDate serviceDate = serviceDate();
    if (request.lookupType() == LookupType.QR) return lookupQr(request.qrRepresentation(), serviceDate);
    UUID accountId = studentProjections.findAccountIdByStudentEnrollment(request.studentEnrollment())
        .orElseThrow(LookupNotFoundException::new);
    List<WashSupervisionQueryPort.LookupCandidate> candidates =
        projections.findAppointments(accountId, serviceDate);
    if (candidates.isEmpty()) throw new LookupNotFoundException();

    List<WashSupervisionQueryPort.LookupCandidate> active = candidates.stream()
        .filter(this::isActive)
        .toList();
    if (active.size() > 1) {
      throw new ProjectionUnavailableException("multiple active appointments contradict the supervisor lookup invariant");
    }
    WashSupervisionQueryPort.LookupCandidate selected = active.isEmpty()
        ? candidates.stream().filter(this::isTerminal).findFirst()
            .orElseThrow(() -> new ProjectionUnavailableException(
                "the projected appointment state cannot be mapped safely"))
        : active.getFirst();
    return composeLookup(serviceDate, selected);
  }

  private SupervisorLookupResponse lookupQr(String representation, LocalDate serviceDate) {
    if (!qrEnabled || !qrRecovery.isReady(serviceDate)) {
      throw new ProjectionUnavailableException("appointment QR lookup is not ready for the service date");
    }
    AppointmentQrReadPort.Mapping mapping = appointmentQr.findByExactRepresentation(representation)
        .orElseThrow(LookupNotFoundException::new);
    WashSupervisionQueryPort.LookupCandidate candidate =
        projections.findAppointment(mapping.appointmentId(), serviceDate)
            .orElseThrow(() -> new ProjectionUnavailableException(
                "appointment QR projection has no matching Appointment view"));
    if (candidate.appointmentVersion() == null
        || mapping.appointmentVersion() > candidate.appointmentVersion()
        || !isActive(candidate)) {
      throw new ProjectionUnavailableException("appointment QR projection contradicts Appointment state");
    }
    return composeLookup(serviceDate, candidate);
  }

  public PendingReassignmentsResponse pendingReassignments() {
    authorization.requireWashSupervisor();
    readiness.requireReady("wash-execution", "appointments", "academic", "person",
        "wash-administration");
    List<PendingReassignmentResponse> items = projections.findPendingReassignments().stream()
        .map(this::compose)
        .toList();
    return new PendingReassignmentsResponse(items);
  }

  public SupervisorExecutionDetailResponse execution(UUID washExecutionId) {
    authorization.requireWashSupervisor();
    readiness.requireReady("wash-execution", "appointments", "academic", "person",
        "wash-administration");
    WashSupervisionQueryPort.ExecutionDetail value = projections.findExecution(washExecutionId)
        .orElseThrow(ExecutionNotFoundException::new);
    return composeExecution(value);
  }

  private SupervisorExecutionDetailResponse composeExecution(
      WashSupervisionQueryPort.ExecutionDetail value) {
    requireExecutionDetailContext(value);
    SubmittedExitMaterials submitted = materials(value.submittedPackageCount(),
        value.submittedGreenPaperCassette8Count(), value.submittedGreenPaperCassette10Count(),
        value.submittedWitnessTapePortionCount());
    SubmittedExitMaterials completed = materials(value.finalPackageCount(),
        value.finalGreenPaperCassette8Count(), value.finalGreenPaperCassette10Count(),
        value.finalWitnessTapePortionCount());
    ActiveResourceAssignment active = assignment(value.activeResourceAssignmentId(),
        value.activeAssignmentType(), value.activeCabinId(), value.activeCabinCode(),
        value.activeCabinName(), value.activeTankId(), value.activeTankCode(),
        value.activeTankName());
    ActiveResourceAssignment last = assignment(value.lastResourceAssignmentId(),
        value.lastAssignmentType(), value.lastCabinId(), value.lastCabinCode(),
        value.lastCabinName(), value.lastTankId(), value.lastTankCode(),
        value.lastTankName());
    if (value.activeAssignmentCount() > 1
        || (value.activeAssignmentCount() == 0 && active != null)
        || (value.activeAssignmentCount() == 1 && active == null)) {
      throw new ProjectionUnavailableException(
          "active resource assignment projection contradicts its count");
    }
    if (!compatibleStatuses(value.executionStatus(), value.appointmentStatus())) {
      throw new ProjectionUnavailableException("appointment and wash execution states contradict each other");
    }
    switch (value.executionStatus()) {
      case "PENDING_ENTRY" -> {
        if (active != null || last != null) {
          throw new ProjectionUnavailableException(
              "pending-entry wash execution has a resource assignment");
        }
      }
      case "ENTRY_REJECTED" -> {
        if (active != null || last != null || isBlank(value.rejectionReason())) {
          throw new ProjectionUnavailableException("rejected entry context is incomplete");
        }
      }
      case "IN_PROGRESS" -> {
        if (active == null) {
          throw new ProjectionUnavailableException("active resource assignment is incomplete");
        }
      }
      case "EXIT_SUBMITTED" -> {
        if (active == null || submitted == null || value.exitSubmittedAt() == null) {
          throw new ProjectionUnavailableException("submitted exit context is incomplete");
        }
      }
      case "COMPLETED" -> {
        if (active != null || last == null || completed == null || value.completedAt() == null) {
          throw new ProjectionUnavailableException("completed wash execution context is incomplete");
        }
      }
      case "CANCELLED" -> {
        if (active != null) {
          throw new ProjectionUnavailableException("cancelled wash execution still has an active resource assignment");
        }
      }
      default -> { }
    }

    SupervisorExecutionCourseSection course = value.courseSectionId() == null ? null
        : new SupervisorExecutionCourseSection(
            value.courseSectionId(), value.courseNrc(), value.courseName());
    AppointmentTimeSlot slot = new AppointmentTimeSlot(value.appointmentTimeSlotId(),
        value.slotStartsAt(), value.slotEndsAt(), value.slotTimezone());
    SupervisorStudent student = new SupervisorStudent(value.studentAccountId(), displayName(value),
        value.studentNumber(), value.currentSemester());
    SupervisorExecutionAppointment appointment = new SupervisorExecutionAppointment(
        value.appointmentId(), value.appointmentVersion(), value.appointmentStatus(),
        value.appointmentType(), value.instrumentCount(), value.pieceType(), course, slot);
    SupervisorExecution execution = new SupervisorExecution(value.washExecutionId(),
        value.executionStatus(), value.executionVersion(), value.executionVersion(), value.arrivedAt(),
        value.rejectionReason(), value.exitSubmittedAt(), submitted, value.completedAt(), completed,
        active, last);
    return new SupervisorExecutionDetailResponse(value.serviceDate(), canComplete(value.executionStatus(), active),
        student, appointment, execution);
  }

  private PendingReassignmentResponse compose(WashSupervisionQueryPort.PendingReassignment value) {
    CourseSectionResponse course = value.courseSectionId() == null ? null
        : new CourseSectionResponse(value.courseSectionId(), value.courseNrc(), value.courseName());
    AppointmentTimeSlotResponse slot = value.appointmentTimeSlotId() == null ? null
        : new AppointmentTimeSlotResponse(value.appointmentTimeSlotId(), value.serviceDate(),
            value.slotStartsAt(), value.slotEndsAt(), value.slotTimezone());
    AppointmentResponse appointment = value.appointmentId() == null ? null
        : new AppointmentResponse(value.appointmentId(), value.appointmentType(),
            value.instrumentCount(), value.pieceType(), value.appointmentStatus(), course, slot);
    StudentResponse student = value.studentAccountId() == null ? null
        : new StudentResponse(value.studentAccountId(), value.personId(), displayName(value),
            value.studentNumber(), value.currentSemester(), value.academicStatus());
    ActiveAssignmentResponse assignment = value.resourceAssignmentId() == null ? null
        : new ActiveAssignmentResponse(value.resourceAssignmentId(), value.assignmentType(),
            value.assignedAt(), resource(value.cabinId(), value.cabinCode(), value.cabinName()),
            resource(value.tankId(), value.tankCode(), value.tankName()));
    SubmittedExitMaterialsResponse submitted = submittedMaterials(value);
    return new PendingReassignmentResponse(value.washExecutionId(), value.executionStatus(),
        value.authorizedAt(), value.executionVersion(), appointment, student, assignment, submitted,
        "NONE");
  }

  private SupervisorLookupResponse composeLookup(LocalDate serviceDate,
      WashSupervisionQueryPort.LookupCandidate value) {
    requireLookupContext(value);
    NextAction action = nextAction(value);
    SubmittedExitMaterials materials = lookupMaterials(value);
    if (action == NextAction.EXIT_REVIEW && "EXIT_SUBMITTED".equals(value.executionStatus())
        && (materials == null || value.exitSubmittedAt() == null)) {
      throw new ProjectionUnavailableException("submitted exit context is incomplete");
    }
    if (action == NextAction.EXIT_REVIEW && value.resourceAssignmentId() == null) {
      throw new ProjectionUnavailableException("active resource assignment is incomplete");
    }

    CourseSectionReference course = value.courseSectionId() == null ? null
        : new CourseSectionReference(value.courseSectionId(), value.courseNrc(), value.courseName());
    AppointmentTimeSlot slot = new AppointmentTimeSlot(value.appointmentTimeSlotId(),
        value.slotStartsAt(), value.slotEndsAt(), value.slotTimezone());
    SupervisorAppointment appointment = new SupervisorAppointment(value.appointmentId(),
        value.appointmentStatus(), value.appointmentType(), value.instrumentCount(), value.pieceType(), course, slot);
    SupervisorWashExecution execution = value.washExecutionId() == null ? null
        : new SupervisorWashExecution(value.washExecutionId(), value.executionStatus(),
            value.executionVersion(), value.arrivedAt(), value.rejectionReason(),
            value.exitSubmittedAt(), materials);
    ActiveResourceAssignment assignment = value.resourceAssignmentId() == null ? null
        : new ActiveResourceAssignment(value.resourceAssignmentId(), value.assignmentType(),
            lookupResource(value.cabinId(), value.cabinCode(), value.cabinName()),
            lookupResource(value.tankId(), value.tankCode(), value.tankName()));
    SupervisorStudent student = new SupervisorStudent(value.studentAccountId(), displayName(value),
        value.studentNumber(), value.currentSemester());
    return new SupervisorLookupResponse(serviceDate, action,
        canComplete(value.executionStatus(), assignment), student, appointment, execution, assignment);
  }

  private boolean canComplete(String executionStatus, ActiveResourceAssignment activeAssignment) {
    if (activeAssignment == null) return false;
    if ("EXIT_SUBMITTED".equals(executionStatus)) return true;
    return directSupervisorCompletionEnabled && "IN_PROGRESS".equals(executionStatus);
  }

  private boolean isActive(WashSupervisionQueryPort.LookupCandidate value) {
    if (hasStatus(TERMINAL_APPOINTMENT_STATUSES, value.appointmentStatus())) return false;
    if (value.washExecutionId() == null) return "SCHEDULED".equals(value.appointmentStatus());
    if (!hasStatus(ACTIVE_EXECUTION_STATUSES, value.executionStatus())) return false;
    return switch (value.executionStatus()) {
      case "PENDING_ENTRY" -> "SCHEDULED".equals(value.appointmentStatus());
      // Reassignment can happen before work starts or after an active resource is lost.
      case "PENDING_REASSIGNMENT" -> "SCHEDULED".equals(value.appointmentStatus())
          || "IN_PROGRESS".equals(value.appointmentStatus());
      case "IN_PROGRESS", "EXIT_SUBMITTED" -> "IN_PROGRESS".equals(value.appointmentStatus());
      default -> false;
    };
  }

  private boolean isTerminal(WashSupervisionQueryPort.LookupCandidate value) {
    return hasStatus(TERMINAL_APPOINTMENT_STATUSES, value.appointmentStatus())
        || hasStatus(TERMINAL_EXECUTION_STATUSES, value.executionStatus());
  }

  private NextAction nextAction(WashSupervisionQueryPort.LookupCandidate value) {
    if (hasStatus(TERMINAL_APPOINTMENT_STATUSES, value.appointmentStatus())) {
      if (hasStatus(ACTIVE_EXECUTION_STATUSES, value.executionStatus())) {
        throw new ProjectionUnavailableException("appointment and wash execution states contradict each other");
      }
      return NextAction.NONE;
    }
    if (value.washExecutionId() == null && "SCHEDULED".equals(value.appointmentStatus())) return NextAction.ENTRY;
    if (value.executionStatus() == null) {
      throw new ProjectionUnavailableException("the projected appointment state cannot be mapped safely");
    }
    return switch (value.executionStatus()) {
      case "PENDING_ENTRY" -> NextAction.ENTRY_DECISION;
      case "PENDING_REASSIGNMENT" -> NextAction.REASSIGNMENT;
      case "IN_PROGRESS", "EXIT_SUBMITTED" -> NextAction.EXIT_REVIEW;
      case "ENTRY_REJECTED", "COMPLETED", "CANCELLED" -> NextAction.NONE;
      default -> throw new ProjectionUnavailableException(
          "the projected appointment state cannot be mapped safely");
    };
  }

  private static boolean hasStatus(Set<String> statuses, String value) {
    return value != null && statuses.contains(value);
  }

  private static void requireLookupContext(WashSupervisionQueryPort.LookupCandidate value) {
    if (value.studentAccountId() == null || value.personId() == null || value.firstName() == null
        || value.studentNumber() == null || value.currentSemester() == null
        || !"ACTIVE".equals(value.academicStatus())
        || value.appointmentId() == null || value.appointmentStatus() == null
        || value.appointmentType() == null || value.instrumentCount() == null || value.pieceType() == null
        || value.courseSectionId() == null || value.courseNrc() == null || value.courseName() == null
        || value.appointmentTimeSlotId() == null
        || value.slotStartsAt() == null || value.slotEndsAt() == null || value.slotTimezone() == null) {
      throw new ProjectionUnavailableException("supervisor lookup projection is incomplete");
    }
    if (value.washExecutionId() != null
        && (value.executionStatus() == null || value.executionVersion() == null || value.arrivedAt() == null)) {
      throw new ProjectionUnavailableException("wash execution projection is incomplete");
    }
    if (value.resourceAssignmentId() != null
        && (value.assignmentType() == null || value.cabinId() == null || value.cabinCode() == null
            || value.cabinName() == null || value.tankId() == null || value.tankCode() == null
            || value.tankName() == null)) {
      throw new ProjectionUnavailableException("active resource assignment projection is incomplete");
    }
  }

  private static SubmittedExitMaterials lookupMaterials(WashSupervisionQueryPort.LookupCandidate value) {
    List<Integer> counts = java.util.Arrays.asList(value.submittedPackageCount(),
        value.submittedGreenPaperCassette8Count(), value.submittedGreenPaperCassette10Count(),
        value.submittedWitnessTapePortionCount());
    long present = counts.stream().filter(java.util.Objects::nonNull).count();
    if (present == 0) return null;
    if (present != counts.size()) {
      throw new ProjectionUnavailableException("submitted exit materials projection is incomplete");
    }
    return new SubmittedExitMaterials(counts.get(0), counts.get(1), counts.get(2), counts.get(3));
  }

  private static void requireExecutionDetailContext(WashSupervisionQueryPort.ExecutionDetail value) {
    if (value.washExecutionId() == null || isBlank(value.executionStatus())
        || !EXECUTION_STATUSES.contains(value.executionStatus())
        || value.executionVersion() == null || value.executionVersion() <= 0
        || value.arrivedAt() == null
        || value.appointmentId() == null || value.appointmentVersion() == null
        || value.appointmentVersion() <= 0 || isBlank(value.appointmentStatus())
        || !APPOINTMENT_STATUSES.contains(value.appointmentStatus())
        || isBlank(value.appointmentType()) || !APPOINTMENT_TYPES.contains(value.appointmentType())
        || value.appointmentTimeSlotId() == null || value.serviceDate() == null
        || value.slotStartsAt() == null || value.slotEndsAt() == null
        || !value.slotStartsAt().isBefore(value.slotEndsAt()) || !isValidZone(value.slotTimezone())
        || value.studentAccountId() == null || value.personId() == null
        || isBlank(value.studentNumber())
        || value.currentSemester() == null || value.currentSemester() <= 0
        || value.activeAssignmentCount() < 0
        || (value.instrumentCount() != null && value.instrumentCount() <= 0)
        || (value.pieceType() != null && !PIECE_TYPES.contains(value.pieceType()))) {
      throw new ProjectionUnavailableException("supervisor execution projection is incomplete");
    }
    ZoneId slotZone = ZoneId.of(value.slotTimezone());
    if (!value.serviceDate().equals(value.slotStartsAt().atZone(slotZone).toLocalDate())) {
      throw new ProjectionUnavailableException("appointment service date contradicts its time slot");
    }
  }

  private static boolean compatibleStatuses(String executionStatus, String appointmentStatus) {
    return switch (executionStatus) {
      case "PENDING_ENTRY" -> "SCHEDULED".equals(appointmentStatus);
      case "ENTRY_REJECTED" -> "ENTRY_REJECTED".equals(appointmentStatus);
      case "PENDING_REASSIGNMENT" -> Set.of("SCHEDULED", "IN_PROGRESS").contains(appointmentStatus);
      case "IN_PROGRESS", "EXIT_SUBMITTED" -> "IN_PROGRESS".equals(appointmentStatus);
      case "COMPLETED" -> "COMPLETED".equals(appointmentStatus);
      case "CANCELLED" -> "CANCELLED".equals(appointmentStatus);
      default -> false;
    };
  }

  private static ActiveResourceAssignment assignment(UUID id, String type,
      UUID cabinId, String cabinCode, String cabinName,
      UUID tankId, String tankCode, String tankName) {
    boolean present = id != null || type != null || cabinId != null || cabinCode != null
        || cabinName != null || tankId != null || tankCode != null || tankName != null;
    if (!present) return null;
    if (id == null || isBlank(type) || !ASSIGNMENT_TYPES.contains(type)
        || cabinId == null || isBlank(cabinCode)
        || isBlank(cabinName) || tankId == null || isBlank(tankCode) || isBlank(tankName)) {
      throw new ProjectionUnavailableException("resource assignment projection is incomplete");
    }
    return new ActiveResourceAssignment(id, type,
        new com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.Resource(
            cabinId, cabinCode, cabinName),
        new com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.Resource(
            tankId, tankCode, tankName));
  }

  private static SubmittedExitMaterials materials(Integer packages, Integer cassette8,
      Integer cassette10, Integer witnessTape) {
    List<Integer> values = java.util.Arrays.asList(packages, cassette8, cassette10, witnessTape);
    long present = values.stream().filter(java.util.Objects::nonNull).count();
    if (present == 0) return null;
    if (present != values.size() || values.stream().anyMatch(value -> value < 0)
        || values.stream().mapToLong(Integer::longValue).sum() == 0L) {
      throw new ProjectionUnavailableException("exit materials projection is incomplete");
    }
    return new SubmittedExitMaterials(packages, cassette8, cassette10, witnessTape);
  }

  private static boolean isValidZone(String value) {
    if (isBlank(value)) return false;
    try {
      ZoneId.of(value);
      return true;
    } catch (java.time.DateTimeException invalidZone) {
      return false;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.Resource lookupResource(
      UUID id, String code, String name) {
    return id == null ? null
        : new com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.Resource(id, code, name);
  }

  private LocalDate serviceDate() {
    return clock.instant().atZone(SERVICE_ZONE).toLocalDate();
  }

  private static SubmittedExitMaterialsResponse submittedMaterials(
      WashSupervisionQueryPort.PendingReassignment value) {
    if (value.submittedPackageCount() == null
        && value.submittedGreenPaperCassette8Count() == null
        && value.submittedGreenPaperCassette10Count() == null
        && value.submittedWitnessTapePortionCount() == null) {
      return null;
    }
    return new SubmittedExitMaterialsResponse(value.submittedPackageCount(),
        value.submittedGreenPaperCassette8Count(), value.submittedGreenPaperCassette10Count(),
        value.submittedWitnessTapePortionCount());
  }

  private static String displayName(WashSupervisionQueryPort.PendingReassignment value) {
    String name = Stream.of(value.firstName(), value.paternalSurname(), value.maternalSurname())
        .filter(part -> part != null && !part.isBlank())
        .reduce((left, right) -> left + " " + right)
        .orElse(null);
    return name == null || name.isBlank() ? null : name;
  }

  private static String displayName(WashSupervisionQueryPort.LookupCandidate value) {
    return Stream.of(value.firstName(), value.paternalSurname(), value.maternalSurname())
        .filter(part -> part != null && !part.isBlank())
        .reduce((left, right) -> left + " " + right)
        .orElseThrow(() -> new ProjectionUnavailableException("student presentation is incomplete"));
  }

  private static String displayName(WashSupervisionQueryPort.ExecutionDetail value) {
    return Stream.of(value.firstName(), value.paternalSurname(), value.maternalSurname())
        .filter(part -> part != null && !part.isBlank())
        .reduce((left, right) -> left + " " + right)
        .orElseThrow(() -> new ProjectionUnavailableException("student presentation is incomplete"));
  }

  private static ResourceResponse resource(java.util.UUID id, String code, String name) {
    return id == null ? null : new ResourceResponse(id, code, name);
  }

  public static class LookupNotFoundException extends RuntimeException {
    public LookupNotFoundException() { super("No appointment was found for today."); }
  }

  public static class ExecutionNotFoundException extends RuntimeException {
    public ExecutionNotFoundException() { super("The Wash execution was not found."); }
  }
}
