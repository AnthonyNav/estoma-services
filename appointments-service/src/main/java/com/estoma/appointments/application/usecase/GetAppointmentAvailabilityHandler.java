package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.port.in.GetAppointmentAvailabilityUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.service.AppointmentCapacityMatcher;
import com.estoma.appointments.domain.service.DailyPenaltyPolicy;
import com.estoma.appointments.domain.service.ImmunocompromisedReservationPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Computes availability from Appointments-owned state; it never reserves capacity. */
@Service
public final class GetAppointmentAvailabilityHandler implements GetAppointmentAvailabilityUseCase {
  static final Duration MAX_SNAPSHOT_FRESHNESS = Duration.ofSeconds(30);
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final AppointmentCapacityProjectionRepository capacities;
  private final ExceptionalAppointmentAuthorizationRepository authorizations;
  private final AcademicProjectionRepository academic;
  private final LocalAuthorizationPolicy authorization;

  public GetAppointmentAvailabilityHandler(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots, AppointmentCapacityProjectionRepository capacities,
      ExceptionalAppointmentAuthorizationRepository authorizations,
      AcademicProjectionRepository academic, AuthorizationProjectionRepository authorization) {
    this.appointments = appointments;
    this.slots = slots;
    this.capacities = capacities;
    this.authorizations = authorizations;
    this.academic = academic;
    this.authorization = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  public Snapshot get(Request request, Instant now) {
    if (now == null) throw new IllegalArgumentException("now is required");
    List<Appointment> studentAppointments = appointments.findForStudentAndDate(
        request.studentAccountId(), request.serviceDate());
    int penaltyPoints = DailyPenaltyPolicy.totalPoints(studentAppointments);
    int completed = Math.toIntExact(studentAppointments.stream()
        .filter(appointment -> "COMPLETED".equals(appointment.status().name())).count());

    List<String> blockers = new ArrayList<>();
    if (studentAppointments.stream().anyMatch(Appointment::isActive)) {
      blockers.add("ACTIVE_APPOINTMENT_EXISTS");
    }
    if (penaltyPoints >= DailyPenaltyPolicy.MAX_POINTS) blockers.add("STUDENT_BLOCKED");
    if (completed >= 2) blockers.add("DAILY_COMPLETED_LIMIT");

    boolean authorizationRequired = completed == 1;
    var usableAuthorization = authorizationRequired
        ? authorizations.findByStudentAndDate(request.studentAccountId(), request.serviceDate()).stream()
            .filter(value -> value.isUsableAt(request.serviceDate(), now))
            .filter(value -> appointments.findByExceptionalAuthorization(
                value.exceptionalAuthorizationId()).stream()
                .noneMatch(Appointment::consumesExceptionalAuthorization))
            .sorted(Comparator.comparing(
                com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization::grantedAt)
                .thenComparing(value -> value.exceptionalAuthorizationId().toString()))
            .findFirst().orElse(null)
        : null;
    if (authorizationRequired && usableAuthorization == null) {
      blockers.add("EXCEPTIONAL_AUTHORIZATION_REQUIRED");
    }
    if (!academicContextIsUsable(request)) blockers.add("ACADEMIC_ENROLLMENT_INVALID");
    if (!authorization.canStudentSchedule(request.studentAccountId())) {
      blockers.add("STUDENT_ACCESS_DENIED");
    }

    List<AvailableTimeSlot> available = availableSlots(request, now);
    Instant freshUntil = available.stream().map(AvailableTimeSlot::bookingDeadlineAt)
        .min(Comparator.naturalOrder()).map(deadline -> deadline.isBefore(now.plus(MAX_SNAPSHOT_FRESHNESS))
            ? deadline : now.plus(MAX_SNAPSHOT_FRESHNESS))
        .orElseGet(() -> now.plus(MAX_SNAPSHOT_FRESHNESS));
    return new Snapshot(request, blockers.isEmpty(), blockers, penaltyPoints, completed,
        authorizationRequired, usableAuthorization != null,
        usableAuthorization == null ? null : usableAuthorization.exceptionalAuthorizationId(),
        available, now, freshUntil);
  }

  private boolean academicContextIsUsable(Request request) {
    if (!academic.reliable()) return false;
    var period = academic.findActivePeriod()
        .filter(value -> value.reliable() && "ACTIVE".equals(value.status())).orElse(null);
    var profile = academic.findStudentProfile(request.studentAccountId())
        .filter(value -> value.reliable() && "ACTIVE".equals(value.academicStatus())).orElse(null);
    var section = academic.findCourseSection(request.courseSectionId())
        .filter(value -> value.reliable() && "ACTIVE".equals(value.status())).orElse(null);
    var enrollment = academic.findStudentCourse(request.studentAccountId(), request.courseSectionId())
        .filter(value -> value.reliable() && value.active()).orElse(null);
    return period != null && profile != null && section != null && enrollment != null
        && period.academicPeriodId().equals(section.academicPeriodId())
        && period.academicPeriodId().equals(enrollment.academicPeriodId());
  }

  private List<AvailableTimeSlot> availableSlots(Request request, Instant now) {
    List<Appointment> activeOnDate = request.appointmentType() == AppointmentType.IMMUNOCOMPROMISED
        ? appointments.findActiveOnDate(request.serviceDate()) : List.of();
    return slots.findActiveFrom(now).stream()
        .filter(slot -> slot.serviceDate().equals(request.serviceDate()))
        .filter(slot -> slot.isBookableAt(now))
        .filter(slot -> !hasImmunocompromisedConflict(slot, request.appointmentType(), activeOnDate))
        .map(slot -> toAvailableSlot(slot, request.appointmentType()))
        .filter(java.util.Objects::nonNull)
        .sorted(Comparator.comparing(AvailableTimeSlot::startsAt)
            .thenComparing(value -> value.appointmentTimeSlotId().toString()))
        .toList();
  }

  private AvailableTimeSlot toAvailableSlot(AppointmentTimeSlot slot, AppointmentType type) {
    var projection = capacities.findBySlotId(slot.appointmentTimeSlotId()).orElse(null);
    if (projection == null) return null;
    int available = AppointmentCapacityMatcher.availableCapacity(
        appointments.findForSlot(slot.appointmentTimeSlotId(), false), type,
        projection.normalCapacity(), projection.journeyCapacity(),
        projection.immunocompromisedCapacity());
    return available <= 0 ? null : new AvailableTimeSlot(slot.appointmentTimeSlotId(),
        slot.startsAt(), slot.endsAt(), available, slot.bookingDeadlineAt());
  }

  private boolean hasImmunocompromisedConflict(AppointmentTimeSlot candidate,
      AppointmentType candidateType, List<Appointment> activeOnDate) {
    return activeOnDate.stream()
        .filter(existing -> existing.appointmentType() == AppointmentType.IMMUNOCOMPROMISED)
        .anyMatch(existing -> slots.findById(existing.appointmentTimeSlotId())
            .map(existingSlot -> ImmunocompromisedReservationPolicy.conflicts(candidate,
                candidateType, existingSlot, existing.appointmentType()))
            .orElse(true));
  }
}
