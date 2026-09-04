package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.application.projection.ProjectionReadModel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Tag("integration")
class ProjectionQueriesPostgresIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_projection_queries")
      .withUsername("bff")
      .withPassword("bff");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired JdbcClient jdbc;
  @Autowired JdbcProjectionReadModelRepository projections;
  @Autowired JdbcWashExitMaterialsProjectionRepository exitMaterials;
  @Autowired JdbcWashSupervisionQueryRepository supervision;
  @Autowired JdbcWashStudentQueryRepository studentQueries;

  @BeforeEach
  void clearReadModels() {
    jdbc.sql("""
        TRUNCATE TABLE mailbox_messages_read_model, mailbox_visits_read_model,
          wash_resource_assignment_read_model, wash_execution_read_model,
          appointment_read_model, appointment_time_slot_read_model,
          exceptional_appointment_authorization_read_model,
          wash_cabin_read_model, wash_tank_read_model,
          course_section_read_model, student_academic_profile_read_model,
          person_presentation_read_model
        CASCADE
        """).update();
  }

  @Test
  void mailboxIsAccountScopedAndCountsMessagesNewerThanEachSystemsVisit() {
    UUID accountId = UUID.randomUUID();
    Instant old = Instant.parse("2026-08-01T10:00:00Z");
    Instant visit = Instant.parse("2026-08-01T11:00:00Z");
    Instant newer = Instant.parse("2026-08-01T12:00:00Z");
    UUID oldId = UUID.randomUUID();
    UUID newerId = UUID.randomUUID();

    projections.upsert(message(oldId, accountId, old));
    projections.upsert(message(newerId, accountId, newer));
    projections.upsert(message(UUID.randomUUID(), UUID.randomUUID(), newer));
    projections.upsert(new ProjectionReadModel.MailboxVisit(
        accountId, "LAVADO_ULTRASONICO", visit, visit));

    assertThat(projections.findVisibleMailboxMessages(accountId, "LAVADO_ULTRASONICO", 20, null).items())
        .extracting(item -> item.inboxMessageId())
        .containsExactly(newerId, oldId);
    assertThat(projections.summarizeMailbox(accountId, "LAVADO_ULTRASONICO"))
        .isEqualTo(new com.estoma.platformbff.application.port.ProjectionQueryPort.MailboxSummary(
            "LAVADO_ULTRASONICO", 2, 1, visit));
  }

  @Test
  void mailboxPaginationIsSystemScopedAndUsesCreatedAtThenMessageIdAsTheStableCursorOrder() {
    UUID accountId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-08-01T12:00:00Z");
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    projections.upsert(message(first, accountId, "LAVADO_ULTRASONICO", createdAt));
    projections.upsert(message(second, accountId, "LAVADO_ULTRASONICO", createdAt));
    projections.upsert(message(UUID.randomUUID(), accountId, "PRACTICAS_PROFESIONALES", createdAt.plusSeconds(1)));

    var firstPage = projections.findVisibleMailboxMessages(accountId, "LAVADO_ULTRASONICO", 1, null);
    var secondPage = projections.findVisibleMailboxMessages(accountId, "LAVADO_ULTRASONICO", 1,
        firstPage.nextCursor());

    assertThat(firstPage.items()).extracting(item -> item.inboxMessageId()).containsExactly(first);
    assertThat(firstPage.nextCursor()).isNotNull();
    assertThat(secondPage.items()).extracting(item -> item.inboxMessageId()).containsExactly(second);
    assertThat(secondPage.nextCursor()).isNull();
  }

  @Test
  void pendingReassignmentsUseTheContractedAuthorizationThenExecutionIdOrdering() {
    Instant first = Instant.parse("2026-08-01T10:00:00Z");
    UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID laterId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    projections.upsert(execution(secondId, first));
    projections.upsert(execution(laterId, first.plusSeconds(60)));
    projections.upsert(execution(firstId, first));
    projections.upsert(new ProjectionReadModel.WashExecution(
        UUID.randomUUID(), null, null, "IN_PROGRESS", null, first, null, null, null, null,
        null, null, null, null, null, null, 1, first));

    assertThat(projections.findPendingReassignments())
        .extracting(item -> item.washExecutionId())
        .containsExactly(firstId, secondId, laterId);
    assertThat(supervision.findPendingReassignments())
        .extracting(item -> item.washExecutionId())
        .containsExactly(firstId, secondId, laterId);
  }

  @Test
  void submittedExitMaterialsUseAnIndependentMonotonicFence() {
    UUID executionId = UUID.randomUUID();
    Instant authorizedAt = Instant.parse("2026-08-01T10:00:00Z");
    projections.upsert(execution(executionId, authorizedAt));

    exitMaterials.projectSubmittedExitMaterials(
        executionId, 1, 2, 3, 4, 7, authorizedAt.plusSeconds(30));
    exitMaterials.projectSubmittedExitMaterials(
        executionId, 9, 9, 9, 9, 6, authorizedAt.plusSeconds(20));

    var item = supervision.findPendingReassignments().getFirst();
    assertThat(item.washExecutionId()).isEqualTo(executionId);
    assertThat(item.submittedPackageCount()).isEqualTo(1);
    assertThat(item.submittedGreenPaperCassette8Count()).isEqualTo(2);
    assertThat(item.submittedGreenPaperCassette10Count()).isEqualTo(3);
    assertThat(item.submittedWitnessTapePortionCount()).isEqualTo(4);
    assertThat(jdbc.sql("""
        SELECT submitted_exit_version FROM wash_execution_read_model
        WHERE wash_execution_id = :executionId
        """).param("executionId", executionId).query(Long.class).single()).isEqualTo(7L);
  }

  @Test
  void studentPrimaryAppointmentPrefersOperationalStatusesThenTheNewestTerminalState() {
    UUID accountId = UUID.randomUUID();
    Instant base = Instant.parse("2026-08-01T10:00:00Z");
    UUID scheduled = appointment(accountId, "SCHEDULED", base);
    UUID completed = appointment(accountId, "COMPLETED", base.plusSeconds(120));

    assertThat(studentQueries.findPrimaryAppointment(accountId, java.time.LocalDate.of(2026, 8, 1)))
        .map(item -> item.appointmentId()).contains(scheduled);

    jdbc.sql("UPDATE appointment_read_model SET status = 'CANCELLED' WHERE appointment_id = :id")
        .param("id", scheduled).update();
    assertThat(studentQueries.findPrimaryAppointment(accountId, java.time.LocalDate.of(2026, 8, 1)))
        .map(item -> item.appointmentId()).contains(completed);

    UUID inProgress = appointment(accountId, "IN_PROGRESS", base.minusSeconds(60));
    assertThat(studentQueries.findPrimaryAppointment(accountId, java.time.LocalDate.of(2026, 8, 1)))
        .map(item -> item.appointmentId()).contains(inProgress);
  }

  @Test
  void laterAppointmentEventsPreserveSchedulingContextAndRejectStaleVersions() {
    UUID accountId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID sectionId = UUID.randomUUID();
    UUID exceptionalAuthorizationId = UUID.randomUUID();
    Instant startsAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant cancellationDeadline = startsAt.minusSeconds(3600);
    jdbc.sql("""
        INSERT INTO appointment_time_slot_read_model (
          appointment_time_slot_id, service_date, starts_at, ends_at, timezone,
          cancellation_deadline_at, status, updated_at)
        VALUES (:id, '2026-08-01', :startsAt, :endsAt, 'America/Mexico_City',
          :deadline, 'ACTIVE', :updatedAt)
        """)
        .param("id", slotId).param("startsAt", java.sql.Timestamp.from(startsAt))
        .param("endsAt", java.sql.Timestamp.from(startsAt.plusSeconds(1800)))
        .param("deadline", java.sql.Timestamp.from(cancellationDeadline))
        .param("updatedAt", java.sql.Timestamp.from(startsAt)).update();

    projections.upsert(new ProjectionReadModel.Appointment(appointmentId, slotId, accountId,
        "NORMAL", sectionId, 8, "HIGH_SPEED", exceptionalAuthorizationId, true,
        "SCHEDULED", null, 1, startsAt));
    projections.upsert(new ProjectionReadModel.Appointment(appointmentId, slotId, accountId,
        null, null, null, null, null, null, "CANCELLED", "STUDENT", 3, startsAt.plusSeconds(120)));
    projections.upsert(new ProjectionReadModel.Appointment(appointmentId, slotId, accountId,
        "JOURNEY", UUID.randomUUID(), 1, "LOW_SPEED", UUID.randomUUID(), true,
        "SCHEDULED", null, 2, startsAt.plusSeconds(60)));

    var projected = studentQueries.findPrimaryAppointment(accountId, java.time.LocalDate.of(2026, 8, 1))
        .orElseThrow();
    assertThat(projected.appointmentStatus()).isEqualTo("CANCELLED");
    assertThat(projected.appointmentVersion()).isEqualTo(3L);
    assertThat(projected.appointmentType()).isEqualTo("NORMAL");
    assertThat(projected.courseSectionId()).isEqualTo(sectionId);
    assertThat(projected.instrumentCount()).isEqualTo(8);
    assertThat(projected.pieceType()).isEqualTo("HIGH_SPEED");
    assertThat(projected.exceptionalAuthorizationId()).isEqualTo(exceptionalAuthorizationId);
    assertThat(projected.exceptionalAuthorizationContextKnown()).isTrue();
    assertThat(projected.cancellationDeadlineAt()).isEqualTo(cancellationDeadline);
  }

  private static ProjectionReadModel.MailboxMessage message(UUID id, UUID accountId, Instant createdAt) {
    return message(id, accountId, "LAVADO_ULTRASONICO", createdAt);
  }

  private static ProjectionReadModel.MailboxMessage message(UUID id, UUID accountId, String systemCode, Instant createdAt) {
    return new ProjectionReadModel.MailboxMessage(id, UUID.randomUUID(), accountId,
        systemCode, "APPOINTMENT", "A title", "A body", createdAt, null, null, createdAt);
  }

  private static ProjectionReadModel.WashExecution execution(UUID id, Instant authorizedAt) {
    return new ProjectionReadModel.WashExecution(id, null, null, "PENDING_REASSIGNMENT", null,
        authorizedAt, null, null, null, null, null, null, null, null, null, null, 1, authorizedAt);
  }

  private UUID appointment(UUID accountId, String status, Instant updatedAt) {
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO appointment_time_slot_read_model (
          appointment_time_slot_id, service_date, starts_at, ends_at, timezone, status, updated_at)
        VALUES (:id, :serviceDate, :startsAt, :endsAt, 'America/Mexico_City', 'ACTIVE', :updatedAt)
        """)
        .param("id", slotId)
        .param("serviceDate", java.time.LocalDate.of(2026, 8, 1))
        .param("startsAt", java.sql.Timestamp.from(updatedAt))
        .param("endsAt", java.sql.Timestamp.from(updatedAt.plusSeconds(1800)))
        .param("updatedAt", java.sql.Timestamp.from(updatedAt))
        .update();
    jdbc.sql("""
        INSERT INTO appointment_read_model (
          appointment_id, appointment_time_slot_id, student_account_id, status, source_version, updated_at)
        VALUES (:id, :slotId, :accountId, :status, 1, :updatedAt)
        """)
        .param("id", appointmentId)
        .param("slotId", slotId)
        .param("accountId", accountId)
        .param("status", status)
        .param("updatedAt", java.sql.Timestamp.from(updatedAt))
        .update();
    return appointmentId;
  }
}
