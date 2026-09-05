package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class AppointmentAvailabilityProducerContractIT extends AbstractPostgresIT {
  @Autowired AppointmentExecutionQueryService queryService;
  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AppointmentCapacityProjectionRepository capacities;
  @Autowired AcademicProjectionRepository academic;
  @Autowired AuthorizationProjectionRepository authorization;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE TABLE appointments, appointment_time_slots, appointment_capacity_projections, "
        + "exceptional_appointment_authorizations, academic_period_projection, "
        + "student_academic_profile_projection, course_section_projection, "
        + "student_course_section_projection, account_role_projection, "
        + "wash_system_access_projection, outbox_messages CASCADE").update();
  }

  @Test
  void durableQueryProducesOwnerMatchedSnapshotInTransactionalOutbox() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant startsAt = now.plusSeconds(3600);
    LocalDate serviceDate = LocalDate.ofInstant(startsAt, ZoneOffset.UTC);
    UUID studentId = UUID.randomUUID();
    UUID courseSectionId = UUID.randomUUID();
    UUID periodId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    var slot = AppointmentTimeSlot.create(slotId, UUID.randomUUID(), UUID.randomUUID(), serviceDate,
        startsAt, startsAt.plusSeconds(3600), ZoneOffset.UTC, now.plusSeconds(1800),
        now.plusSeconds(1200), startsAt.plusSeconds(600));
    slots.save(slot);
    capacities.save(new AppointmentCapacityProjectionRepository.CapacityProjection(
        slotId, 2, 1, 1, 1, now));
    appointments.save(Appointment.scheduled(UUID.randomUUID(), slotId, UUID.randomUUID(),
        courseSectionId, AppointmentType.JOURNEY, 1, PieceType.HIGH_SPEED, null,
        now.minusSeconds(60)));
    seedAcademic(studentId, courseSectionId, periodId, now);
    seedAuthorization(studentId, now);

    UUID queryId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    var payload = mapper.createObjectNode()
        .put("studentAccountId", studentId.toString())
        .put("serviceDate", serviceDate.toString())
        .put("appointmentType", "NORMAL")
        .put("instrumentCount", 15)
        .put("pieceType", "HIGH_SPEED")
        .put("courseSectionId", courseSectionId.toString());
    queryService.answer(new MessageEnvelope(queryId, "GetAppointmentAvailability", 1,
        MessageKind.QUERY, "bff", now, correlationId, null, null, null, null, null,
        new WireActor("SYSTEM", null, "bff"), payload));

    var row = jdbc.sql("SELECT message_id,message_version,message_kind,aggregate_id,"
            + "aggregate_version,correlation_id,causation_id,CAST(payload AS text) payload "
            + "FROM outbox_messages WHERE message_type='GetAppointmentAvailabilityResult'")
        .query((rs, index) -> new Object[]{
            rs.getObject("message_id", UUID.class), rs.getInt("message_version"),
            rs.getString("message_kind"), rs.getObject("aggregate_id", UUID.class),
            rs.getObject("aggregate_version", Long.class), rs.getObject("correlation_id", UUID.class),
            rs.getObject("causation_id", UUID.class), rs.getString("payload")}).single();
    var result = mapper.readTree((String) row[7]);
    assertThat(row[1]).isEqualTo(1);
    assertThat(row[2]).isEqualTo("RESULT");
    assertThat(row[3]).isNull();
    assertThat(row[4]).isNull();
    assertThat(row[5]).isEqualTo(correlationId);
    assertThat(row[6]).isEqualTo(queryId);
    assertThat(result.path("data").path("snapshotId").asString())
        .isEqualTo(row[0].toString());
    assertThat(result.path("data").path("requestContext")).isEqualTo(payload);
    assertThat(result.path("data").path("canSchedule").asBoolean()).isTrue();
    assertThat(result.path("data").path("blockingReasons")).isEmpty();
    assertThat(result.path("data").path("availableTimeSlots")).singleElement().satisfies(node -> {
      assertThat(node.path("appointmentTimeSlotId").asString()).isEqualTo(slotId.toString());
      assertThat(node.path("availableCapacity").asInt()).isEqualTo(2);
    });
  }

  private void seedAcademic(UUID studentId, UUID courseSectionId, UUID periodId, Instant now) {
    UUID profileId = UUID.randomUUID();
    academic.upsertAcademicPeriod(new AcademicProjectionRepository.AcademicPeriodProjection(
        periodId, "2026-2", "ACTIVE", 1, now, true));
    academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
        profileId, studentId, 5, "ACTIVE", 1, now, true));
    academic.upsertCourseSection(new AcademicProjectionRepository.CourseSectionProjection(
        courseSectionId, periodId, "ACTIVE", 1, now, true));
    academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(
        profileId, studentId, courseSectionId, periodId, now.minusSeconds(86_400), null,
        1, now, true));
  }

  private void seedAuthorization(UUID studentId, Instant now) {
    authorization.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(
        studentId, UUID.randomUUID(), "ALUMNO", 1, now, true));
    authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
        UUID.randomUUID(), studentId, UUID.randomUUID(), "LAVADO_ULTRASONICO", "ACTIVE", 1,
        now, true));
  }
}
