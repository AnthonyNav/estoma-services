package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.in.CreateAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.in.ScheduleAppointmentUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AppointmentExecutionProducerContractIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-24T13:00:00Z");
  private static final Instant START = Instant.parse("2026-08-24T14:00:00Z");
  private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 24);

  @Autowired CreateAppointmentTimeSlotUseCase createSlots;
  @Autowired ScheduleAppointmentUseCase scheduleAppointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired WashAdministrationProjectionRepository administration;
  @Autowired AuthorizationProjectionRepository authorization;
  @Autowired AcademicProjectionRepository academic;
  @Autowired AppointmentCapacityProjectionRepository capacity;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE TABLE appointments, appointment_time_slots, appointment_capacity_projections, "
        + "exceptional_appointment_authorizations, academic_period_projection, "
        + "student_academic_profile_projection, course_section_projection, student_course_section_projection, "
        + "account_role_projection, wash_system_access_projection, service_calendar_projection, "
        + "service_schedule_projection, administrative_closure_projection, cabin_projection, tank_projection, "
        + "resource_priority_projection, command_idempotency_records, outbox_messages CASCADE").update();
  }

  @Test
  void appointmentTimeSlotCreatedCarriesCalendarAndOperationalContextForExecution() {
    UUID calendarId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    seedAdministrativeSchedule(calendarId, scheduleId);

    var result = createSlots.create(new CreateAppointmentTimeSlotUseCase.Command(
        CommandMetadata.direct(ActorContext.system("appointments"), NOW),
        slotId, scheduleId, SERVICE_DATE, START, START.plusSeconds(3600), ZoneOffset.UTC,
        START.minusSeconds(600), START.minusSeconds(1200), START.plusSeconds(600), NOW));

    assertThat(result.status().name()).isEqualTo("APPLIED");
    JsonNode payload = outboxPayload("AppointmentTimeSlotCreated");
    assertThat(payload.path("appointmentTimeSlotId").asString()).isEqualTo(slotId.toString());
    assertThat(payload.path("calendarId").asString()).isEqualTo(calendarId.toString());
    assertThat(payload.path("serviceDate").asString()).isEqualTo(SERVICE_DATE.toString());
    assertThat(payload.path("startsAt").asString()).isEqualTo(START.toString());
    assertThat(payload.path("endsAt").asString()).isEqualTo(START.plusSeconds(3600).toString());
    assertThat(payload.path("timezone").asString()).isEqualTo("Z");
    assertThat(payload.path("arrivalToleranceUntil").asString())
        .isEqualTo(START.plusSeconds(600).toString());
    assertThat(payload.path("status").asString()).isEqualTo("ACTIVE");
    assertThat(payload.path("version").asLong()).isEqualTo(1L);
    assertThat(OutboxRelay.eventRoutingKey("AppointmentTimeSlotCreated"))
        .isEqualTo("appointments.appointment-time-slot.created");
  }

  @Test
  void appointmentScheduledCarriesExecutionProjectionContext() {
    UUID calendarId = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    seedAdministrativeSchedule(calendarId, scheduleId);
    slots.save(AppointmentTimeSlot.create(
        slotId, scheduleId, calendarId, SERVICE_DATE, START, START.plusSeconds(3600), ZoneOffset.UTC,
        START.minusSeconds(600), START.minusSeconds(1200), START.plusSeconds(600)));

    UUID student = UUID.randomUUID();
    UUID courseSection = UUID.randomUUID();
    UUID academicPeriod = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    seedStudent(student, courseSection, academicPeriod, profile);
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(
        slotId, 2, 1, 1, 1, NOW));

    var result = scheduleAppointments.schedule(new ScheduleAppointmentUseCase.Command(
        CommandMetadata.direct(ActorContext.account(student), NOW),
        student, slotId, courseSection, AppointmentType.NORMAL, 2, PieceType.HIGH_SPEED, null, NOW));

    assertThat(result.status().name()).isEqualTo("APPLIED");
    JsonNode payload = outboxPayload("AppointmentScheduled");
    assertThat(payload.path("appointmentId").asString()).isNotBlank();
    assertThat(payload.path("studentAccountId").asString()).isEqualTo(student.toString());
    assertThat(payload.path("appointmentTimeSlotId").asString()).isEqualTo(slotId.toString());
    assertThat(payload.path("courseSectionId").asString()).isEqualTo(courseSection.toString());
    assertThat(payload.path("appointmentType").asString()).isEqualTo("NORMAL");
    assertThat(payload.path("instrumentCount").asInt()).isEqualTo(2);
    assertThat(payload.path("pieceType").asString()).isEqualTo("HIGH_SPEED");
    assertThat(payload.path("status").asString()).isEqualTo("SCHEDULED");
    assertThat(payload.path("version").asLong()).isEqualTo(1L);
    assertThat(OutboxRelay.eventRoutingKey("AppointmentScheduled"))
        .isEqualTo("appointments.appointment.scheduled");
  }

  private void seedAdministrativeSchedule(UUID calendarId, UUID scheduleId) {
    administration.upsertServiceCalendar(new WashAdministrationProjectionRepository.ServiceCalendarProjection(
        calendarId, "CAL", "UTC", SERVICE_DATE.minusDays(1), SERVICE_DATE.plusDays(1),
        "ACTIVE", 1, NOW));
    administration.upsertServiceSchedule(new WashAdministrationProjectionRepository.ServiceScheduleProjection(
        scheduleId, calendarId, 1, LocalTime.of(8, 0), LocalTime.of(17, 0),
        60, "ACTIVE", 1, NOW));
  }

  private void seedStudent(UUID student, UUID courseSection, UUID academicPeriod, UUID profile) {
    authorization.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(
        student, UUID.randomUUID(), "ALUMNO", 1, NOW, true));
    authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
        UUID.randomUUID(), student, UUID.randomUUID(), "LAVADO_ULTRASONICO", "ACTIVE", 1, NOW, true));
    academic.upsertAcademicPeriod(new AcademicProjectionRepository.AcademicPeriodProjection(
        academicPeriod, "2026-2", "ACTIVE", 1, NOW, true));
    academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
        profile, student, 5, "ACTIVE", 1, NOW, true));
    academic.upsertCourseSection(new AcademicProjectionRepository.CourseSectionProjection(
        courseSection, academicPeriod, "ACTIVE", 1, NOW, true));
    academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(
        profile, student, courseSection, academicPeriod, NOW.minusSeconds(86400), null, 1, NOW, true));
  }

  private JsonNode outboxPayload(String messageType) {
    String json = jdbc.sql("SELECT CAST(payload AS text) FROM outbox_messages "
        + "WHERE message_type=:type ORDER BY occurred_at DESC LIMIT 1")
        .param("type", messageType).query(String.class).single();
    return mapper.readTree(json);
  }
}
