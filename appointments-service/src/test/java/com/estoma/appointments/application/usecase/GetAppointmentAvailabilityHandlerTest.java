package com.estoma.appointments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetAppointmentAvailabilityHandlerTest {
  private static final Instant NOW = Instant.parse("2026-09-04T14:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2026, 9, 4);
  private final UUID studentId = UUID.randomUUID();
  private final UUID courseSectionId = UUID.randomUUID();
  private final UUID periodId = UUID.randomUUID();
  private final AppointmentRepository appointments = mock(AppointmentRepository.class);
  private final AppointmentTimeSlotRepository slots = mock(AppointmentTimeSlotRepository.class);
  private final AppointmentCapacityProjectionRepository capacities =
      mock(AppointmentCapacityProjectionRepository.class);
  private final ExceptionalAppointmentAuthorizationRepository authorizations =
      mock(ExceptionalAppointmentAuthorizationRepository.class);
  private final AcademicProjectionRepository academic = mock(AcademicProjectionRepository.class);
  private final AuthorizationProjectionRepository authorization =
      mock(AuthorizationProjectionRepository.class);
  private GetAppointmentAvailabilityHandler handler;

  @BeforeEach
  void setUp() {
    handler = new GetAppointmentAvailabilityHandler(
        appointments, slots, capacities, authorizations, academic, authorization);
    seedAuthorizedStudent();
    seedUsableAcademicContext();
    when(appointments.findForStudentAndDate(studentId, DATE)).thenReturn(List.of());
    when(appointments.findActiveOnDate(DATE)).thenReturn(List.of());
    when(authorizations.findByStudentAndDate(studentId, DATE)).thenReturn(List.of());
  }

  @Test
  void exposesOnlyBookableSlotsWithOwnerMatchedCompatibleCapacity() {
    AppointmentTimeSlot atDeadline = slot(NOW, NOW.plusSeconds(3600));
    AppointmentTimeSlot exhausted = slot(NOW.plusSeconds(60), NOW.plusSeconds(3660));
    AppointmentTimeSlot expired = slot(NOW.minusSeconds(1), NOW.plusSeconds(3500));
    when(slots.findActiveFrom(NOW)).thenReturn(List.of(expired, exhausted, atDeadline));
    when(capacities.findBySlotId(atDeadline.appointmentTimeSlotId())).thenReturn(Optional.of(
        capacity(atDeadline, 1, 1, 1)));
    when(capacities.findBySlotId(exhausted.appointmentTimeSlotId())).thenReturn(Optional.of(
        capacity(exhausted, 1, 1, 1)));
    when(appointments.findForSlot(atDeadline.appointmentTimeSlotId(), false))
        .thenReturn(List.of(appointment(atDeadline, AppointmentType.JOURNEY)));
    when(appointments.findForSlot(exhausted.appointmentTimeSlotId(), false))
        .thenReturn(List.of(appointment(exhausted, AppointmentType.NORMAL),
            appointment(exhausted, AppointmentType.JOURNEY)));

    var result = handler.get(request(AppointmentType.NORMAL), NOW);

    assertThat(result.canSchedule()).isTrue();
    assertThat(result.availableTimeSlots()).singleElement().satisfies(slot -> {
      assertThat(slot.appointmentTimeSlotId()).isEqualTo(atDeadline.appointmentTimeSlotId());
      assertThat(slot.availableCapacity()).isEqualTo(1);
      assertThat(slot.bookingDeadlineAt()).isEqualTo(NOW);
    });
    assertThat(result.freshUntil()).isEqualTo(NOW);
  }

  @Test
  void computesPenaltyAndDailyLimitBlockingReasonsFromOwnerHistory() {
    AppointmentTimeSlot slot = slot(NOW.plusSeconds(600), NOW.plusSeconds(4200));
    Appointment missed1 = appointment(slot, AppointmentType.NORMAL).markMissed(NOW.minusSeconds(30));
    Appointment missed2 = appointment(slot, AppointmentType.NORMAL).markMissed(NOW.minusSeconds(20));
    Appointment missed3 = appointment(slot, AppointmentType.NORMAL).markMissed(NOW.minusSeconds(10));
    Appointment completed1 = appointment(slot, AppointmentType.NORMAL)
        .start(NOW.minusSeconds(100)).complete(UUID.randomUUID(), NOW.minusSeconds(90));
    Appointment completed2 = appointment(slot, AppointmentType.NORMAL)
        .start(NOW.minusSeconds(80)).complete(UUID.randomUUID(), NOW.minusSeconds(70));
    when(appointments.findForStudentAndDate(studentId, DATE))
        .thenReturn(List.of(missed1, missed2, missed3, completed1, completed2));
    when(slots.findActiveFrom(NOW)).thenReturn(List.of());

    var result = handler.get(request(AppointmentType.NORMAL), NOW);

    assertThat(result.canSchedule()).isFalse();
    assertThat(result.dailyPenaltyPoints()).isEqualTo(3);
    assertThat(result.dailyCompletedAppointments()).isEqualTo(2);
    assertThat(result.blockingReasons()).containsExactly("STUDENT_BLOCKED", "DAILY_COMPLETED_LIMIT");
    assertThat(result.exceptionalAuthorizationRequired()).isFalse();
  }

  @Test
  void exposesOnlyAUsableUnconsumedExceptionalAuthorizationForSecondAppointment() {
    AppointmentTimeSlot slot = slot(NOW.plusSeconds(600), NOW.plusSeconds(4200));
    Appointment completed = appointment(slot, AppointmentType.NORMAL)
        .start(NOW.minusSeconds(100)).complete(UUID.randomUUID(), NOW.minusSeconds(90));
    UUID unavailableId = UUID.randomUUID();
    UUID availableId = UUID.randomUUID();
    var unavailable = ExceptionalAppointmentAuthorization.grant(unavailableId, studentId, DATE,
        UUID.randomUUID(), "already consumed", NOW.minusSeconds(300), null);
    var available = ExceptionalAppointmentAuthorization.grant(availableId, studentId, DATE,
        UUID.randomUUID(), "second appointment", NOW.minusSeconds(200), null);
    when(appointments.findForStudentAndDate(studentId, DATE)).thenReturn(List.of(completed));
    when(authorizations.findByStudentAndDate(studentId, DATE))
        .thenReturn(List.of(unavailable, available));
    when(appointments.findByExceptionalAuthorization(unavailableId))
        .thenReturn(List.of(Appointment.scheduled(UUID.randomUUID(), slot.appointmentTimeSlotId(),
            studentId, courseSectionId, AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED,
            unavailableId, NOW.minusSeconds(100))));
    when(appointments.findByExceptionalAuthorization(availableId)).thenReturn(List.of());
    when(slots.findActiveFrom(any())).thenReturn(List.of(slot));
    when(capacities.findBySlotId(slot.appointmentTimeSlotId()))
        .thenReturn(Optional.of(capacity(slot, 1, 0, 0)));
    when(appointments.findForSlot(slot.appointmentTimeSlotId(), false)).thenReturn(List.of());

    var result = handler.get(request(AppointmentType.NORMAL), NOW);

    assertThat(result.canSchedule()).isTrue();
    assertThat(result.exceptionalAuthorizationRequired()).isTrue();
    assertThat(result.exceptionalAuthorizationAvailable()).isTrue();
    assertThat(result.exceptionalAuthorizationId()).isEqualTo(availableId);
  }

  @Test
  void snapshotDoesNotReserveAndNextEvaluationObservesConcurrentDemand() {
    AppointmentTimeSlot slot = slot(NOW.plusSeconds(600), NOW.plusSeconds(4200));
    when(slots.findActiveFrom(any())).thenReturn(List.of(slot));
    when(capacities.findBySlotId(slot.appointmentTimeSlotId()))
        .thenReturn(Optional.of(capacity(slot, 1, 0, 0)));
    when(appointments.findForSlot(slot.appointmentTimeSlotId(), false))
        .thenReturn(List.of(), List.of(appointment(slot, AppointmentType.NORMAL)));

    var before = handler.get(request(AppointmentType.NORMAL), NOW);
    var afterConcurrentSchedule = handler.get(request(AppointmentType.NORMAL), NOW.plusSeconds(1));

    assertThat(before.availableTimeSlots()).singleElement()
        .extracting(GetAppointmentAvailabilityUseCase.AvailableTimeSlot::availableCapacity)
        .isEqualTo(1);
    assertThat(afterConcurrentSchedule.availableTimeSlots()).isEmpty();
    assertThat(before.canSchedule()).isTrue();
    assertThat(afterConcurrentSchedule.canSchedule()).isTrue();
    assertThat(afterConcurrentSchedule.blockingReasons()).isEmpty();
  }

  @Test
  void authorizationIsEvaluatedForStudentIdentityAndBlockersAreStableAndUnique() {
    when(authorization.reliable()).thenReturn(false);
    when(academic.reliable()).thenReturn(false);
    AppointmentTimeSlot slot = slot(NOW.plusSeconds(600), NOW.plusSeconds(4200));
    Appointment active = appointment(slot, AppointmentType.NORMAL);
    Appointment missed1 = appointment(slot, AppointmentType.NORMAL).markMissed(NOW.minusSeconds(50));
    Appointment missed2 = appointment(slot, AppointmentType.NORMAL).markMissed(NOW.minusSeconds(40));
    Appointment missed3 = appointment(slot, AppointmentType.NORMAL).markMissed(NOW.minusSeconds(30));
    Appointment completed1 = appointment(slot, AppointmentType.NORMAL)
        .start(NOW.minusSeconds(100)).complete(UUID.randomUUID(), NOW.minusSeconds(90));
    Appointment completed2 = appointment(slot, AppointmentType.NORMAL)
        .start(NOW.minusSeconds(80)).complete(UUID.randomUUID(), NOW.minusSeconds(70));
    when(appointments.findForStudentAndDate(studentId, DATE))
        .thenReturn(List.of(active, missed1, missed2, missed3, completed1, completed2));
    when(slots.findActiveFrom(NOW)).thenReturn(List.of());

    var result = handler.get(request(AppointmentType.NORMAL), NOW);

    assertThat(result.canSchedule()).isFalse();
    assertThat(result.blockingReasons()).containsExactly(
        "ACTIVE_APPOINTMENT_EXISTS", "STUDENT_BLOCKED", "DAILY_COMPLETED_LIMIT",
        "ACADEMIC_ENROLLMENT_INVALID", "STUDENT_ACCESS_DENIED");
    assertThat(result.blockingReasons()).doesNotHaveDuplicates();
  }

  @Test
  void exceptionalAuthorizationBlockerPrecedesAcademicAndAccessBlockers() {
    when(authorization.reliable()).thenReturn(false);
    when(academic.reliable()).thenReturn(false);
    AppointmentTimeSlot slot = slot(NOW.plusSeconds(600), NOW.plusSeconds(4200));
    Appointment completed = appointment(slot, AppointmentType.NORMAL)
        .start(NOW.minusSeconds(100)).complete(UUID.randomUUID(), NOW.minusSeconds(90));
    when(appointments.findForStudentAndDate(studentId, DATE)).thenReturn(List.of(completed));
    when(slots.findActiveFrom(NOW)).thenReturn(List.of());

    var result = handler.get(request(AppointmentType.NORMAL), NOW);

    assertThat(result.blockingReasons()).containsExactly(
        "EXCEPTIONAL_AUTHORIZATION_REQUIRED", "ACADEMIC_ENROLLMENT_INVALID",
        "STUDENT_ACCESS_DENIED");
    assertThat(result.blockingReasons()).doesNotHaveDuplicates();
  }

  private void seedUsableAcademicContext() {
    when(academic.reliable()).thenReturn(true);
    when(academic.findActivePeriod()).thenReturn(Optional.of(
        new AcademicProjectionRepository.AcademicPeriodProjection(
            periodId, "2026-2", "ACTIVE", 1, NOW, true)));
    when(academic.findStudentProfile(studentId)).thenReturn(Optional.of(
        new AcademicProjectionRepository.StudentAcademicProfileProjection(
            UUID.randomUUID(), studentId, 5, "ACTIVE", 1, NOW, true)));
    when(academic.findCourseSection(courseSectionId)).thenReturn(Optional.of(
        new AcademicProjectionRepository.CourseSectionProjection(
            courseSectionId, periodId, "ACTIVE", 1, NOW, true)));
    when(academic.findStudentCourse(studentId, courseSectionId)).thenReturn(Optional.of(
        new AcademicProjectionRepository.StudentCourseSectionProjection(
            UUID.randomUUID(), studentId, courseSectionId, periodId,
            NOW.minusSeconds(86_400), null, 1, NOW, true)));
  }

  private void seedAuthorizedStudent() {
    when(authorization.reliable()).thenReturn(true);
    when(authorization.findAccountRole(studentId)).thenReturn(Optional.of(
        new AuthorizationProjectionRepository.AccountRoleProjection(
            studentId, UUID.randomUUID(), "ALUMNO", 1, NOW, true)));
    when(authorization.findSystemAccess(studentId, "LAVADO_ULTRASONICO")).thenReturn(Optional.of(
        new AuthorizationProjectionRepository.SystemAccessProjection(UUID.randomUUID(), studentId,
            UUID.randomUUID(), "LAVADO_ULTRASONICO", "ACTIVE", 1, NOW, true)));
  }

  private GetAppointmentAvailabilityUseCase.Request request(AppointmentType type) {
    return new GetAppointmentAvailabilityUseCase.Request(
        studentId, DATE, type, 1, PieceType.HIGH_SPEED, courseSectionId);
  }

  private AppointmentTimeSlot slot(Instant bookingDeadline, Instant startsAt) {
    return AppointmentTimeSlot.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DATE,
        startsAt, startsAt.plusSeconds(3600), ZoneOffset.UTC, bookingDeadline,
        bookingDeadline.minusSeconds(600), startsAt.plusSeconds(600));
  }

  private Appointment appointment(AppointmentTimeSlot slot, AppointmentType type) {
    return Appointment.scheduled(UUID.randomUUID(), slot.appointmentTimeSlotId(), studentId,
        courseSectionId, type, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(600));
  }

  private AppointmentCapacityProjectionRepository.CapacityProjection capacity(
      AppointmentTimeSlot slot, int normal, int journey, int immunocompromised) {
    return new AppointmentCapacityProjectionRepository.CapacityProjection(
        slot.appointmentTimeSlotId(), normal, journey, immunocompromised, 1, NOW);
  }
}
