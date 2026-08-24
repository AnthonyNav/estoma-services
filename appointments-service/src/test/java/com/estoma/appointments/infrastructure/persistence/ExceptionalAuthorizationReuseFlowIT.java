package com.estoma.appointments.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.ScheduleAppointmentUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.application.usecase.HandleWashExecutionCancelledHandler;
import com.estoma.appointments.application.usecase.ScheduleAppointmentHandler;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ExceptionalAuthorizationReuseFlowIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-24T13:00:00Z");
  private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 24);

  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AppointmentCapacityProjectionRepository capacity;
  @Autowired ExceptionalAppointmentAuthorizationRepository authorizations;
  @Autowired AcademicProjectionRepository academic;
  @Autowired AuthorizationProjectionRepository authorization;
  @Autowired ScheduleAppointmentHandler schedule;
  @Autowired HandleWashExecutionCancelledHandler cancelled;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("""
        TRUNCATE TABLE
          pending_capacity_evaluation, wash_execution_transition_projection,
          pending_wash_execution_events, processed_messages, outbox_messages,
          command_idempotency_records, appointments, appointment_capacity_projections,
          appointment_time_slots, exceptional_appointment_authorizations,
          academic_period_projection, student_academic_profile_projection,
          course_section_projection, student_course_section_projection,
          account_role_projection, wash_system_access_projection
        CASCADE
        """).update();
  }

  @Test
  void secondAppointmentRequiresAuthorizationAndClinicCancellationMakesSameAuthorizationReusable() {
    UUID studentId = UUID.randomUUID();
    UUID courseSectionId = UUID.randomUUID();
    seedStudent(studentId, courseSectionId);

    UUID firstSlotId = saveSlot(Instant.parse("2026-08-24T10:00:00Z"), 2);
    UUID secondSlotId = saveSlot(Instant.parse("2026-08-24T14:00:00Z"), 2);
    UUID reuseSlotId = saveSlot(Instant.parse("2026-08-24T15:00:00Z"), 2);

    Appointment first = Appointment.scheduled(UUID.randomUUID(), firstSlotId, studentId,
        courseSectionId, AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null,
        Instant.parse("2026-08-24T09:50:00Z"))
        .start(Instant.parse("2026-08-24T10:05:00Z"))
        .complete(UUID.randomUUID(), Instant.parse("2026-08-24T10:40:00Z"));
    appointments.save(first);

    UUID secondAppointmentId = UUID.randomUUID();
    var withoutAuthorization = schedule.schedule(command(studentId, secondAppointmentId,
        secondSlotId, courseSectionId, null, NOW));
    assertThat(withoutAuthorization.status().name()).isEqualTo("REJECTED");
    assertThat(withoutAuthorization.code()).isEqualTo("EXCEPTIONAL_AUTHORIZATION_REQUIRED");
    assertThat(appointments.findById(secondAppointmentId)).isEmpty();

    UUID authorizationId = UUID.randomUUID();
    authorizations.save(ExceptionalAppointmentAuthorization.grant(authorizationId, studentId,
        SERVICE_DATE, UUID.randomUUID(), "authorized second appointment", NOW.minusSeconds(60),
        Instant.parse("2026-08-24T23:59:59Z")));

    var scheduledSecond = schedule.schedule(command(studentId, secondAppointmentId,
        secondSlotId, courseSectionId, authorizationId, NOW.plusSeconds(1)));
    assertThat(scheduledSecond.status().name()).isEqualTo("APPLIED");
    assertThat(appointments.findById(secondAppointmentId).orElseThrow().consumesExceptionalAuthorization())
        .isTrue();

    UUID executionId = UUID.randomUUID();
    UUID cancellationEventId = UUID.randomUUID();
    var clinicCancellation = cancelled.handle(new ExecutionEvent(
        cancellationEventId, cancellationEventId, executionId, secondAppointmentId,
        "WashExecutionCancelled", 1, NOW.plusSeconds(2), null, NOW.plusSeconds(2),
        UUID.randomUUID(), null, "CLINIC", "CAPACITY_LOSS", "clinic capacity loss", "{}"));
    assertThat(clinicCancellation.status().name()).isEqualTo("APPLIED");
    Appointment cancelledSecond = appointments.findById(secondAppointmentId).orElseThrow();
    assertThat(cancelledSecond.status().name()).isEqualTo("CANCELLED");
    assertThat(cancelledSecond.consumesExceptionalAuthorization()).isFalse();

    UUID reusedAppointmentId = UUID.randomUUID();
    var reused = schedule.schedule(command(studentId, reusedAppointmentId, reuseSlotId,
        courseSectionId, authorizationId, NOW.plusSeconds(3)));
    assertThat(reused.status().name()).isEqualTo("APPLIED");
    assertThat(appointments.findById(reusedAppointmentId).orElseThrow().consumesExceptionalAuthorization())
        .isTrue();

    var history = appointments.findByExceptionalAuthorization(authorizationId);
    assertThat(history).hasSize(2);
    assertThat(history).extracting(Appointment::appointmentId)
        .containsExactlyInAnyOrder(secondAppointmentId, reusedAppointmentId);
    assertThat(history.stream().filter(Appointment::consumesExceptionalAuthorization).count()).isEqualTo(1);
  }

  private ScheduleAppointmentUseCase.Command command(UUID studentId, UUID appointmentId,
      UUID slotId, UUID courseSectionId, UUID authorizationId, Instant now) {
    return new ScheduleAppointmentUseCase.Command(
        CommandMetadata.direct(ActorContext.account(studentId), now), appointmentId, studentId,
        slotId, courseSectionId, AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED,
        authorizationId, now);
  }

  private UUID saveSlot(Instant startsAt, int normalCapacity) {
    UUID slotId = UUID.randomUUID();
    slots.save(AppointmentTimeSlot.create(slotId, UUID.randomUUID(), UUID.randomUUID(),
        SERVICE_DATE, startsAt, startsAt.plusSeconds(3600), ZoneOffset.UTC,
        startsAt.minusSeconds(600), startsAt.minusSeconds(1200), startsAt.plusSeconds(600)));
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(
        slotId, normalCapacity, 0, 0, 1, NOW));
    return slotId;
  }

  private void seedStudent(UUID studentId, UUID courseSectionId) {
    UUID periodId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    academic.upsertAcademicPeriod(new AcademicProjectionRepository.AcademicPeriodProjection(
        periodId, "2026-2", "ACTIVE", 1, NOW, true));
    academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
        profileId, studentId, 5, "ACTIVE", 1, NOW, true));
    academic.upsertCourseSection(new AcademicProjectionRepository.CourseSectionProjection(
        courseSectionId, periodId, "ACTIVE", 1, NOW, true));
    academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(
        profileId, studentId, courseSectionId, periodId, NOW.minusSeconds(86400), null,
        1, NOW, true));
    authorization.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(
        studentId, UUID.randomUUID(), "ALUMNO", 1, NOW, true));
    authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
        UUID.randomUUID(), studentId, UUID.randomUUID(), "LAVADO_ULTRASONICO", "ACTIVE",
        1, NOW, true));
  }
}
