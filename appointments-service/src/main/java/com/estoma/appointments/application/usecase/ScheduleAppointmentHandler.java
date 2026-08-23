package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.ScheduleAppointmentUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.error.DomainViolationException;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.service.AppointmentCapacityMatcher;
import com.estoma.appointments.domain.service.DailyPenaltyPolicy;
import com.estoma.appointments.domain.service.ImmunocompromisedReservationPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Complete booking workflow. Slot/capacity rows are locked before final demand validation. */
@Service
public class ScheduleAppointmentHandler implements ScheduleAppointmentUseCase {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final AppointmentCapacityProjectionRepository capacity;
  private final ExceptionalAppointmentAuthorizationRepository authorizations;
  private final AcademicProjectionRepository academic;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public ScheduleAppointmentHandler(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots, AppointmentCapacityProjectionRepository capacity,
      ExceptionalAppointmentAuthorizationRepository authorizations,
      AcademicProjectionRepository academic, AuthorizationProjectionRepository authorization,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox) {
    this.appointments = appointments;
    this.slots = slots;
    this.capacity = capacity;
    this.authorizations = authorizations;
    this.academic = academic;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  @Transactional
  public ApplicationResult schedule(Command command) {
    Instant now = command.now();
    var metadata = command.metadata();
    String fingerprint = ApplicationSupport.fingerprint(command.studentAccountId(),
        command.appointmentTimeSlotId(), command.courseSectionId(), command.appointmentType(),
        command.instrumentCount(), command.pieceType(), command.exceptionalAuthorizationId());
    Optional<ApplicationResult> gate = ApplicationSupport.claimClient(idempotency, outbox, metadata,
        "ScheduleAppointment", fingerprint, now);
    if (gate.isPresent()) return gate.get();

    ApplicationResult result;
    try {
      result = validateAndSchedule(command);
    } catch (IllegalArgumentException exception) {
      result = ApplicationResult.rejected(code(exception), exception.getMessage());
    }
    ApplicationSupport.appendResult(outbox, metadata, "ScheduleAppointment", result, now);
    ApplicationSupport.complete(idempotency, metadata, "ScheduleAppointment", result, now);
    return result;
  }

  private ApplicationResult validateAndSchedule(Command command) {
    if (!policy.canSchedule(command.metadata(), command.studentAccountId())) {
      return ApplicationResult.rejected("FORBIDDEN", "student is not authorized for appointments");
    }
    if (!academic.reliable()) {
      return ApplicationResult.rejected("ACADEMIC_PROJECTION_UNRELIABLE", "academic projection is not reliable");
    }
    var period = academic.findActivePeriod()
        .filter(value -> value.reliable() && "ACTIVE".equals(value.status())).orElse(null);
    var profile = academic.findStudentProfile(command.studentAccountId())
        .filter(value -> value.reliable() && "ACTIVE".equals(value.academicStatus())).orElse(null);
    var section = academic.findCourseSection(command.courseSectionId())
        .filter(value -> value.reliable() && "ACTIVE".equals(value.status())).orElse(null);
    var enrollment = academic.findStudentCourse(command.studentAccountId(), command.courseSectionId())
        .filter(value -> value.reliable() && value.active()).orElse(null);
    if (period == null || profile == null || section == null || enrollment == null
        || !period.academicPeriodId().equals(section.academicPeriodId())
        || !period.academicPeriodId().equals(enrollment.academicPeriodId())) {
      return ApplicationResult.rejected("ACADEMIC_ENROLLMENT_INVALID",
          "active academic period, course section and enrollment are required");
    }

    AppointmentTimeSlot slot = slots.findByIdForUpdate(command.appointmentTimeSlotId()).orElse(null);
    if (slot == null) return ApplicationResult.rejected("SLOT_NOT_FOUND", "appointment time slot was not found");
    if (!slot.serviceDate().equals(command.now().atZone(slot.timezone()).toLocalDate())) {
      return ApplicationResult.rejected("SERVICE_DATE_NOT_CURRENT", "appointments can only be scheduled for the current service date");
    }
    if (!slot.isBookableAt(command.now())) {
      return ApplicationResult.rejected("SLOT_NOT_BOOKABLE", "slot is inactive or past its booking deadline");
    }

    List<Appointment> studentAppointments = appointments.findForStudentAndDate(
        command.studentAccountId(), slot.serviceDate());
    if (studentAppointments.stream().anyMatch(Appointment::isActive)) {
      return ApplicationResult.rejected("ACTIVE_APPOINTMENT_EXISTS", "student already has an active appointment");
    }
    if (!DailyPenaltyPolicy.maySchedule(studentAppointments)) {
      return ApplicationResult.rejected("STUDENT_BLOCKED", "student has reached the daily penalty limit");
    }

    long completed = studentAppointments.stream().filter(a -> a.status().name().equals("COMPLETED")).count();
    UUID authorizationId = command.exceptionalAuthorizationId();
    if (completed >= 2) return ApplicationResult.rejected("DAILY_COMPLETED_LIMIT", "student has reached the maximum of two completed appointments");
    if (completed == 0 && authorizationId != null) return ApplicationResult.rejected("EXCEPTIONAL_AUTHORIZATION_NOT_REQUIRED", "exceptional authorization is only consumed by the additional appointment");
    if (completed == 1 && authorizationId == null) return ApplicationResult.rejected("EXCEPTIONAL_AUTHORIZATION_REQUIRED", "the additional appointment requires exceptional authorization");
    if (authorizationId != null) {
      var authorization = authorizations.findByIdForUpdate(authorizationId).orElse(null);
      if (authorization == null) return ApplicationResult.rejected("EXCEPTIONAL_AUTHORIZATION_NOT_FOUND", "exceptional authorization was not found");
      if (!authorization.studentAccountId().equals(command.studentAccountId())) return ApplicationResult.rejected("EXCEPTIONAL_AUTHORIZATION_STUDENT_MISMATCH", "authorization belongs to another student");
      if (!authorization.isUsableAt(slot.serviceDate(), command.now())) return ApplicationResult.rejected("EXCEPTIONAL_AUTHORIZATION_UNAVAILABLE", "exceptional authorization is cancelled or expired");
      if (appointments.findActiveByExceptionalAuthorizationForUpdate(authorizationId).isPresent()) return ApplicationResult.rejected("EXCEPTIONAL_AUTHORIZATION_ALREADY_USED", "exceptional authorization is already consumed");
    }

    var projection = capacity.findBySlotIdForUpdate(slot.appointmentTimeSlotId()).orElse(null);
    if (projection == null) return ApplicationResult.rejected("CAPACITY_UNAVAILABLE", "slot capacity has not been projected");
    List<Appointment> slotAppointments = appointments.findForSlot(slot.appointmentTimeSlotId(), true);
    if (!AppointmentCapacityMatcher.canFit(slotAppointments, command.appointmentType(), projection.normalCapacity(), projection.journeyCapacity(), projection.immunocompromisedCapacity())) {
      return ApplicationResult.rejected("CAPACITY_EXHAUSTED", "slot has no compatible capacity");
    }

    if (command.appointmentType() == AppointmentType.IMMUNOCOMPROMISED
        && appointments.findActiveOnDate(slot.serviceDate()).stream().anyMatch(existing -> {
          if (existing.appointmentType() != AppointmentType.IMMUNOCOMPROMISED) return false;
          return slots.findById(existing.appointmentTimeSlotId())
              .map(other -> ImmunocompromisedReservationPolicy.conflicts(slot, command.appointmentType(), other, existing.appointmentType()))
              .orElse(true);
        })) {
      return ApplicationResult.rejected("IMMUNOCOMPROMISED_RESERVATION_CONFLICT",
          "preventive cleaning reservation overlaps another immunocompromised appointment");
    }

    Appointment appointment = Appointment.scheduled(UUID.randomUUID(), slot.appointmentTimeSlotId(),
        command.studentAccountId(), command.courseSectionId(), command.appointmentType(),
        command.instrumentCount(), command.pieceType(), authorizationId, command.now());
    appointments.save(appointment);
    ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentScheduled",
        appointment.appointmentId(), appointment.version(), "{\"appointmentId\":\""
            + appointment.appointmentId() + "\",\"studentAccountId\":\""
            + appointment.studentAccountId() + "\",\"appointmentTimeSlotId\":\""
            + appointment.appointmentTimeSlotId() + "\",\"courseSectionId\":\""
            + appointment.courseSectionId() + "\",\"appointmentType\":\""
            + appointment.appointmentType() + "\",\"instrumentCount\":"
            + appointment.instrumentCount() + ",\"pieceType\":\""
            + appointment.pieceType() + "\",\"status\":\"SCHEDULED\",\"version\":"
            + appointment.version() + "}", command.now());
    return ApplicationResult.applied(appointment.appointmentId(), appointment.version(), "appointment scheduled");
  }

  private static String code(RuntimeException exception) {
    return exception instanceof DomainViolationException ? "INVALID_APPOINTMENT" : "INVALID_COMMAND";
  }
}
