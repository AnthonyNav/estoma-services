package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.port.WashStudentQueryPort;
import com.estoma.platformbff.application.port.AppointmentQrReadPort;
import com.estoma.platformbff.application.projection.AppointmentQrRecoveryService;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentFormContextResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentTimeSlot;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.ActiveResourceAssignment;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.CourseSection;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.FormStudent;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.ExitMaterials;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeAppointment;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeStudent;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentCancellationAction;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.WashExecution;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Service
public class WashStudentQueryService {
  private static final ZoneId SERVICE_ZONE = ZoneId.of("America/Mexico_City");
  private static final java.util.Set<String> QR_ACTIVE_STATUSES = java.util.Set.of("SCHEDULED", "IN_PROGRESS");

  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final WashStudentQueryPort projections;
  private final Clock clock;
  private final AppointmentQrReadPort appointmentQr;
  private final AppointmentQrRecoveryService qrRecovery;
  private final boolean qrEnabled;

  @Autowired
  public WashStudentQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashStudentQueryPort projections, Clock clock,
      AppointmentQrReadPort appointmentQr, AppointmentQrRecoveryService qrRecovery,
      @Value("${platform.wash.qr.enabled:false}") boolean qrEnabled) {
    this.authorization = authorization;
    this.readiness = readiness;
    this.projections = projections;
    this.clock = clock;
    this.appointmentQr = appointmentQr;
    this.qrRecovery = qrRecovery;
    this.qrEnabled = qrEnabled;
  }

  public WashStudentQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashStudentQueryPort projections, Clock clock) {
    this(authorization, readiness, projections, clock, null, null, false);
  }

  public StudentHomeResponse home() {
    UUID accountId = authorization.requireStudent();
    readiness.requireReady("person", "academic", "appointments", "wash-execution");
    var student = requireStudent(accountId);
    LocalDate serviceDate = clock.instant().atZone(SERVICE_ZONE).toLocalDate();
    StudentHomeAppointment appointment = projections.findPrimaryAppointment(accountId, serviceDate)
        .map(value -> appointment(value, clock, serviceDate))
        .orElse(null);
    return new StudentHomeResponse(homeStudent(student), serviceDate, appointment);
  }

  public AppointmentFormContextResponse formContext() {
    UUID accountId = authorization.requireStudent();
    readiness.requireReady("person", "academic");
    var student = requireStudent(accountId);
    List<CourseSection> sections = projections.findAvailableCourseSections(accountId).stream()
        .map(value -> new CourseSection(value.courseSectionId(), value.nrc(), value.name()))
        .toList();
    return new AppointmentFormContextResponse(new FormStudent(fullName(student), student.studentEnrollment(),
        student.currentSemester()), sections);
  }

  private WashStudentQueryPort.Student requireStudent(UUID accountId) {
    return projections.findStudent(accountId).orElseThrow(() ->
        new ProjectionUnavailableException("student projection is incomplete"));
  }

  private static StudentHomeStudent homeStudent(WashStudentQueryPort.Student student) {
    return new StudentHomeStudent(student.firstName(), fullName(student), student.studentEnrollment(),
        student.currentSemester());
  }

  private StudentHomeAppointment appointment(WashStudentQueryPort.Appointment value, Clock clock,
      LocalDate serviceDate) {
    if (value.appointmentVersion() == null) {
      throw new ProjectionUnavailableException("appointment version is incomplete");
    }
    CourseSection course = value.courseSectionId() == null ? null
        : new CourseSection(value.courseSectionId(), value.courseNrc(), value.courseName());
    AppointmentTimeSlot slot = value.appointmentTimeSlotId() == null ? null
        : new AppointmentTimeSlot(value.appointmentTimeSlotId(), value.startsAt(), value.endsAt(), value.timezone(),
            value.cancellationDeadlineAt());
    ActiveResourceAssignment assignment = value.resourceAssignmentId() == null ? null
        : new ActiveResourceAssignment(value.resourceAssignmentId(), value.cabinId(), value.cabinCode(),
            value.cabinName(), value.tankId(), value.tankCode(), value.tankName());
    ActiveResourceAssignment lastAssignment = value.lastResourceAssignmentId() == null ? null
        : new ActiveResourceAssignment(value.lastResourceAssignmentId(), value.lastCabinId(), value.lastCabinCode(),
            value.lastCabinName(), value.lastTankId(), value.lastTankCode(), value.lastTankName());
    ExitMaterials submitted = materials(value.submittedPackageCount(), value.submittedGreenPaperCassette8Count(),
        value.submittedGreenPaperCassette10Count(), value.submittedWitnessTapePortionCount());
    ExitMaterials completed = materials(value.finalPackageCount(), value.finalGreenPaperCassette8Count(),
        value.finalGreenPaperCassette10Count(), value.finalWitnessTapePortionCount());
    requireExecutionContext(value, assignment, lastAssignment, submitted, completed);
    WashExecution execution = value.washExecutionId() == null ? null
        : new WashExecution(value.washExecutionId(), value.washExecutionStatus(),
            value.washExecutionVersion(), value.washExecutionVersion(), value.washExecutionArrivedAt(),
            value.washExecutionRejectionReason(), value.exitSubmittedAt(), submitted,
            value.completedAt(), completed, assignment, lastAssignment);
    return new StudentHomeAppointment(value.appointmentId(), value.appointmentStatus(), value.appointmentVersion(),
        value.exceptionalAuthorizationId() != null, studentCancellationAction(value, clock),
        value.appointmentType(), value.instrumentCount(), value.pieceType(), course, slot, execution,
        qrUsageContext(value, clock), qrRepresentation(value, serviceDate));
  }

  private String qrRepresentation(WashStudentQueryPort.Appointment appointment, LocalDate serviceDate) {
    if (!qrEnabled) return null;
    if (!isQrActive(appointment)) return null;
    if (!qrRecovery.isReady(serviceDate)) {
      throw new ProjectionUnavailableException("appointment QR recovery is not ready for the service date");
    }
    AppointmentQrReadPort.Mapping mapping = appointmentQr.findByAppointmentId(appointment.appointmentId())
        .orElseThrow(() -> new ProjectionUnavailableException("appointment QR projection is incomplete"));
    if (!mapping.appointmentId().equals(appointment.appointmentId())
        || mapping.qrRepresentation() == null
        || mapping.appointmentVersion() > appointment.appointmentVersion()) {
      throw new ProjectionUnavailableException("appointment QR projection contradicts Appointment state");
    }
    return mapping.qrRepresentation();
  }

  private static boolean isQrActive(WashStudentQueryPort.Appointment appointment) {
    if (!QR_ACTIVE_STATUSES.contains(appointment.appointmentStatus())) return false;
    if ("SCHEDULED".equals(appointment.appointmentStatus())) {
      if (appointment.washExecutionId() == null) return true;
      if ("PENDING_ENTRY".equals(appointment.washExecutionStatus())
          || "PENDING_REASSIGNMENT".equals(appointment.washExecutionStatus())) return true;
    } else if (appointment.washExecutionId() != null
        && ("PENDING_REASSIGNMENT".equals(appointment.washExecutionStatus())
            || "IN_PROGRESS".equals(appointment.washExecutionStatus())
            || "EXIT_SUBMITTED".equals(appointment.washExecutionStatus()))) {
      return true;
    }
    throw new ProjectionUnavailableException("appointment and wash execution states contradict each other");
  }

  private static void requireExecutionContext(WashStudentQueryPort.Appointment value,
      ActiveResourceAssignment active, ActiveResourceAssignment last,
      ExitMaterials submitted, ExitMaterials completed) {
    if (value.washExecutionId() == null) return;
    if (value.washExecutionVersion() == null) {
      throw new ProjectionUnavailableException("wash execution version is incomplete");
    }
    if (value.washExecutionStatus() == null) {
      throw new ProjectionUnavailableException("wash execution status is incomplete");
    }
    switch (value.washExecutionStatus()) {
      case "IN_PROGRESS" -> {
        if (active == null) throw new ProjectionUnavailableException("active resource assignment is incomplete");
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
      default -> { }
    }
  }

  private static ExitMaterials materials(Integer packages, Integer cassette8, Integer cassette10,
      Integer witnessTape) {
    if (packages == null && cassette8 == null && cassette10 == null && witnessTape == null) return null;
    if (packages == null || cassette8 == null || cassette10 == null || witnessTape == null) {
      throw new ProjectionUnavailableException("exit materials projection is incomplete");
    }
    return new ExitMaterials(packages, cassette8, cassette10, witnessTape);
  }

  private static StudentCancellationAction studentCancellationAction(WashStudentQueryPort.Appointment value,
      Clock clock) {
    if (!"SCHEDULED".equals(value.appointmentStatus()) || !value.exceptionalAuthorizationContextKnown()
        || value.appointmentTimeSlotId() == null
        || value.cancellationDeadlineAt() == null) return StudentCancellationAction.NOT_APPLICABLE;
    return clock.instant().isAfter(value.cancellationDeadlineAt())
        ? StudentCancellationAction.DEADLINE_PASSED : StudentCancellationAction.AVAILABLE;
  }

  private static String qrUsageContext(WashStudentQueryPort.Appointment value, Clock clock) {
    if ("PENDING_REASSIGNMENT".equals(value.washExecutionStatus())
        || "CANCELLED".equals(value.appointmentStatus())
        || "MISSED".equals(value.appointmentStatus())
        || "ENTRY_REJECTED".equals(value.appointmentStatus())
        || "COMPLETED".equals(value.appointmentStatus())) return "NONE";
    if ("IN_PROGRESS".equals(value.appointmentStatus()) && "IN_PROGRESS".equals(value.washExecutionStatus())) {
      return "STUDENT_EXIT";
    }
    if ("IN_PROGRESS".equals(value.appointmentStatus()) && "EXIT_SUBMITTED".equals(value.washExecutionStatus())) {
      return "SUPERVISOR_EXIT_REVIEW";
    }
    if ("SCHEDULED".equals(value.appointmentStatus())
        && (value.arrivalToleranceUntil() == null || !clock.instant().isAfter(value.arrivalToleranceUntil()))) {
      return "ENTRY";
    }
    return "NONE";
  }

  private static String fullName(WashStudentQueryPort.Student student) {
    return java.util.stream.Stream.of(student.firstName(), student.paternalSurname(), student.maternalSurname())
        .filter(value -> value != null && !value.isBlank())
        .reduce((left, right) -> left + " " + right)
        .orElse("");
  }

}
