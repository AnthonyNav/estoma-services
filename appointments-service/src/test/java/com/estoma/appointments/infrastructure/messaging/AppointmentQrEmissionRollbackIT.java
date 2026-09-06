package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.in.ScheduleAppointmentUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentQrRepresentationIssuer;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(AppointmentQrEmissionRollbackIT.FailingIssuerConfiguration.class)
class AppointmentQrEmissionRollbackIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-24T13:00:00Z");
  private static final Instant START = Instant.parse("2026-08-24T14:00:00Z");
  private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 24);

  @Autowired ScheduleAppointmentUseCase scheduleAppointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AuthorizationProjectionRepository authorization;
  @Autowired AcademicProjectionRepository academic;
  @Autowired AppointmentCapacityProjectionRepository capacity;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE TABLE appointments, appointment_time_slots, appointment_capacity_projections, "
        + "exceptional_appointment_authorizations, academic_period_projection, "
        + "student_academic_profile_projection, course_section_projection, student_course_section_projection, "
        + "account_role_projection, wash_system_access_projection, command_idempotency_records, "
        + "outbox_messages CASCADE").update();
  }

  @Test
  void issuerFailureRollsBackAppointmentIdempotencyAndOutboxInPostgresTransaction() {
    UUID slotId = UUID.randomUUID();
    slots.save(AppointmentTimeSlot.create(
        slotId, UUID.randomUUID(), UUID.randomUUID(), SERVICE_DATE, START,
        START.plusSeconds(3600), ZoneOffset.UTC, START.minusSeconds(600),
        START.minusSeconds(1200), START.plusSeconds(600)));

    UUID student = UUID.randomUUID();
    UUID courseSection = UUID.randomUUID();
    UUID academicPeriod = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    seedStudent(student, courseSection, academicPeriod, profile);
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(
        slotId, 2, 1, 1, 1, NOW));

    UUID appointmentId = UUID.randomUUID();
    var command = new ScheduleAppointmentUseCase.Command(
        CommandMetadata.direct(ActorContext.account(student), NOW),
        appointmentId, student, slotId, courseSection, AppointmentType.NORMAL,
        2, PieceType.HIGH_SPEED, null, NOW);

    assertThatThrownBy(() -> scheduleAppointments.schedule(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled QR issuer failure");

    assertThat(count("appointments")).isZero();
    assertThat(count("command_idempotency_records")).isZero();
    assertThat(count("outbox_messages")).isZero();
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

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  @TestConfiguration
  static class FailingIssuerConfiguration {
    @Bean
    @Primary
    AppointmentQrRepresentationIssuer failingAppointmentQrRepresentationIssuer() {
      return new AppointmentQrRepresentationIssuer() {
        @Override
        public boolean enabled() {
          return true;
        }

        @Override
        public String issue(UUID appointmentId) {
          throw new IllegalStateException("controlled QR issuer failure");
        }
      };
    }
  }
}
