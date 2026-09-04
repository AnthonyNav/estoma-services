package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.port.WashStudentQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentFormContextResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentTimeSlot;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.ActiveResourceAssignment;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.CourseSection;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.FormStudent;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeAppointment;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeStudent;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentCancellationAction;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.SupervisorEntryLookupResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.WashExecution;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.EntryLookupRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WashStudentQueryService {
  private static final ZoneId SERVICE_ZONE = ZoneId.of("America/Mexico_City");

  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final WashStudentQueryPort projections;
  private final Clock clock;

  public WashStudentQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashStudentQueryPort projections, Clock clock) {
    this.authorization = authorization;
    this.readiness = readiness;
    this.projections = projections;
    this.clock = clock;
  }

  public StudentHomeResponse home() {
    UUID accountId = authorization.requireStudent();
    readiness.requireReady("person", "academic", "appointments", "wash-execution");
    var student = requireStudent(accountId);
    LocalDate serviceDate = clock.instant().atZone(SERVICE_ZONE).toLocalDate();
    StudentHomeAppointment appointment = projections.findPrimaryAppointment(accountId, serviceDate)
        .map(value -> appointment(value, clock))
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

  /**
   * Composes the manual student-enrollment lookup entirely from local read models. QR remains
   * intentionally unavailable until its owner-backed lookup projection exists.
   */
  public SupervisorEntryLookupResponse entryLookup(EntryLookupRequest request) {
    authorization.requireWashSupervisor();
    readiness.requireReady("person", "academic", "appointments", "wash-execution", "wash-administration");
    if ("QR".equals(request.lookupType())) {
      throw new ProjectionUnavailableException("QR entry lookup is gated pending its local projection");
    }
    UUID accountId = projections.findAccountIdByStudentEnrollment(request.studentEnrollment())
        .orElseThrow(EntryLookupNotFoundException::new);
    var student = requireStudent(accountId);
    LocalDate serviceDate = clock.instant().atZone(SERVICE_ZONE).toLocalDate();
    var appointment = projections.findPrimaryAppointment(accountId, serviceDate)
        .map(value -> appointment(value, clock))
        .orElseThrow(EntryLookupNotFoundException::new);
    return new SupervisorEntryLookupResponse(serviceDate,
        new FormStudent(fullName(student), student.studentEnrollment(), student.currentSemester()),
        appointment, appointment.washExecution());
  }

  private WashStudentQueryPort.Student requireStudent(UUID accountId) {
    return projections.findStudent(accountId).orElseThrow(() ->
        new ProjectionUnavailableException("student projection is incomplete"));
  }

  private static StudentHomeStudent homeStudent(WashStudentQueryPort.Student student) {
    return new StudentHomeStudent(student.firstName(), fullName(student), student.studentEnrollment(),
        student.currentSemester());
  }

  private static StudentHomeAppointment appointment(WashStudentQueryPort.Appointment value, Clock clock) {
    CourseSection course = value.courseSectionId() == null ? null
        : new CourseSection(value.courseSectionId(), value.courseNrc(), value.courseName());
    AppointmentTimeSlot slot = value.appointmentTimeSlotId() == null ? null
        : new AppointmentTimeSlot(value.appointmentTimeSlotId(), value.startsAt(), value.endsAt(), value.timezone(),
            value.cancellationDeadlineAt());
    ActiveResourceAssignment assignment = value.resourceAssignmentId() == null ? null
        : new ActiveResourceAssignment(value.resourceAssignmentId(), value.cabinId(), value.cabinCode(),
            value.cabinName(), value.tankId(), value.tankCode(), value.tankName());
    WashExecution execution = value.washExecutionId() == null ? null
        : new WashExecution(value.washExecutionId(), value.washExecutionStatus(),
            value.washExecutionVersion(), value.washExecutionArrivedAt(),
            value.washExecutionRejectionReason(), assignment);
    return new StudentHomeAppointment(value.appointmentId(), value.appointmentStatus(), value.appointmentVersion(),
        value.exceptionalAuthorizationId() != null, studentCancellationAction(value, clock),
        value.appointmentType(), value.instrumentCount(), value.pieceType(), course, slot, execution,
        qrUsageContext(value, clock), null);
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

  public static class EntryLookupNotFoundException extends RuntimeException {
    public EntryLookupNotFoundException() { super("No appointment was found for today."); }
  }
}
