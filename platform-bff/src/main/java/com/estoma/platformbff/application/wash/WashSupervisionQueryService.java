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

  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final WashSupervisionQueryPort projections;
  private final WashStudentQueryPort studentProjections;
  private final Clock clock;
  private final AppointmentQrReadPort appointmentQr;
  private final AppointmentQrRecoveryService qrRecovery;
  private final boolean qrEnabled;

  @Autowired
  public WashSupervisionQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashSupervisionQueryPort projections,
      WashStudentQueryPort studentProjections, Clock clock, AppointmentQrReadPort appointmentQr,
      AppointmentQrRecoveryService qrRecovery,
      @Value("${platform.wash.qr.enabled:false}") boolean qrEnabled) {
    this.authorization = authorization;
    this.readiness = readiness;
    this.projections = projections;
    this.studentProjections = studentProjections;
    this.clock = clock;
    this.appointmentQr = appointmentQr;
    this.qrRecovery = qrRecovery;
    this.qrEnabled = qrEnabled;
  }

  public WashSupervisionQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashSupervisionQueryPort projections,
      WashStudentQueryPort studentProjections, Clock clock) {
    this(authorization, readiness, projections, studentProjections, clock, null, null, false);
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
    return new SupervisorLookupResponse(serviceDate, action, student, appointment, execution, assignment);
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

  private static ResourceResponse resource(java.util.UUID id, String code, String name) {
    return id == null ? null : new ResourceResponse(id, code, name);
  }

  public static class LookupNotFoundException extends RuntimeException {
    public LookupNotFoundException() { super("No appointment was found for today."); }
  }
}
