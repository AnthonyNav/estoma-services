package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.application.projection.ProjectionReadModel;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
  @Autowired JdbcWashCompletionProjectionRepository completions;
  @Autowired JdbcWashSupervisionQueryRepository supervision;
  @Autowired JdbcWashStudentQueryRepository studentQueries;
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactionManager;

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
  void completionProjectsOfficialMaterialsAndReleasesTheAssignmentWithAReplaySafeFence() {
    UUID executionId = UUID.randomUUID();
    UUID assignmentId = UUID.randomUUID();
    Instant completedAt = Instant.parse("2026-08-01T11:00:00Z");
    jdbc.sql("""
        INSERT INTO wash_execution_read_model (
          wash_execution_id, status, completed_at, execution_version, updated_at)
        VALUES (:executionId, 'COMPLETED', :completedAt, 6, :completedAt)
        """).param("executionId", executionId)
        .param("completedAt", java.sql.Timestamp.from(completedAt)).update();
    jdbc.sql("""
        INSERT INTO wash_resource_assignment_read_model (
          resource_assignment_id, wash_execution_id, assignment_type, assigned_at,
          source_message_id, source_version, updated_at)
        VALUES (:assignmentId, :executionId, 'INITIAL', :assignedAt, :messageId, 4, :assignedAt)
        """).param("assignmentId", assignmentId).param("executionId", executionId)
        .param("assignedAt", java.sql.Timestamp.from(completedAt.minusSeconds(600)))
        .param("messageId", UUID.randomUUID()).update();

    completions.projectCompletion(executionId, 5, 6, 7, 8, 6, completedAt);
    completions.projectCompletion(executionId, 9, 9, 9, 9, 5, completedAt.minusSeconds(30));
    completions.projectCompletion(executionId, 5, 6, 7, 8, 6, completedAt);

    var row = jdbc.sql("""
        SELECT final_package_count, final_green_paper_cassette_8_count,
               final_green_paper_cassette_10_count, final_witness_tape_portion_count,
               final_exit_version
        FROM wash_execution_read_model WHERE wash_execution_id = :executionId
        """).param("executionId", executionId).query().singleRow();
    assertThat(row.get("final_package_count")).isEqualTo(5);
    assertThat(row.get("final_green_paper_cassette_8_count")).isEqualTo(6);
    assertThat(row.get("final_green_paper_cassette_10_count")).isEqualTo(7);
    assertThat(row.get("final_witness_tape_portion_count")).isEqualTo(8);
    assertThat(row.get("final_exit_version")).isEqualTo(6L);
    assertThat(jdbc.sql("""
        SELECT released_at FROM wash_resource_assignment_read_model
        WHERE resource_assignment_id = :assignmentId
        """).param("assignmentId", assignmentId).query(Instant.class).single()).isEqualTo(completedAt);
  }

  @Test
  void delayedAssignmentBecomesTheReleasedHistoryOfACompletedExecution() {
    UUID accountId = UUID.randomUUID();
    UUID appointmentId = appointment(accountId, "IN_PROGRESS", Instant.parse("2026-08-01T10:00:00Z"));
    UUID executionId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    Instant completedAt = Instant.parse("2026-08-01T11:00:00Z");
    jdbc.sql("""
        INSERT INTO wash_cabin_read_model(cabin_id, code, name, status, source_version, updated_at)
        VALUES (:id, 'C-01', 'Cabina 1', 'ACTIVE', 1, :updatedAt)
        """).param("id", cabinId).param("updatedAt", java.sql.Timestamp.from(completedAt)).update();
    jdbc.sql("""
        INSERT INTO wash_tank_read_model(tank_id, cabin_id, code, name, status, source_version, updated_at)
        VALUES (:id, :cabinId, 'T-01', 'Tanque 1', 'ACTIVE', 1, :updatedAt)
        """).param("id", tankId).param("cabinId", cabinId)
        .param("updatedAt", java.sql.Timestamp.from(completedAt)).update();
    projections.upsert(new ProjectionReadModel.WashExecution(
        executionId, appointmentId, "NORMAL", "COMPLETED", null, null, null, null, completedAt, null,
        null, null, null, null, null, null, 6, completedAt));
    completions.projectCompletion(executionId, 5, 6, 7, 8, 6, completedAt);

    projections.upsert(new ProjectionReadModel.ResourceAssignment(
        UUID.randomUUID(), executionId, appointmentId, null, cabinId, tankId,
        "INITIAL", completedAt.minusSeconds(600), null, UUID.randomUUID(), 4,
        completedAt.minusSeconds(600)));

    var assignment = jdbc.sql("""
        SELECT released_at, source_version FROM wash_resource_assignment_read_model
        WHERE wash_execution_id = :executionId
        """).param("executionId", executionId).query().singleRow();
    assertThat(((java.sql.Timestamp) assignment.get("released_at")).toInstant()).isEqualTo(completedAt);
    assertThat(assignment.get("source_version")).isEqualTo(4L);
  }

  @Test
  void concurrentAssignmentCannotReactivateACompletedExecution() throws Exception {
    UUID accountId = UUID.randomUUID();
    UUID appointmentId = appointment(accountId, "IN_PROGRESS", Instant.parse("2026-08-01T10:00:00Z"));
    UUID executionId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    Instant completedAt = Instant.parse("2026-08-01T11:00:00Z");
    jdbc.sql("""
        INSERT INTO wash_cabin_read_model(cabin_id, code, name, status, source_version, updated_at)
        VALUES (:id, 'C-01', 'Cabina 1', 'ACTIVE', 1, :updatedAt)
        """).param("id", cabinId).param("updatedAt", java.sql.Timestamp.from(completedAt)).update();
    jdbc.sql("""
        INSERT INTO wash_tank_read_model(tank_id, cabin_id, code, name, status, source_version, updated_at)
        VALUES (:id, :cabinId, 'T-01', 'Tanque 1', 'ACTIVE', 1, :updatedAt)
        """).param("id", tankId).param("cabinId", cabinId)
        .param("updatedAt", java.sql.Timestamp.from(completedAt)).update();
    projections.upsert(new ProjectionReadModel.WashExecution(
        executionId, appointmentId, "NORMAL", "IN_PROGRESS", null, null, completedAt.minusSeconds(900), null,
        null, null, null, null, null, null, null, null, 5, completedAt.minusSeconds(900)));

    jdbc.sql("""
        CREATE FUNCTION block_test_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.wash_execution_id = '%s'::uuid THEN
            PERFORM pg_advisory_xact_lock(918273645);
          END IF;
          RETURN NEW;
        END
        $$
        """.formatted(executionId)).update();
    jdbc.sql("""
        CREATE TRIGGER block_test_assignment_before_insert
        BEFORE INSERT ON wash_resource_assignment_read_model
        FOR EACH ROW EXECUTE FUNCTION block_test_assignment()
        """).update();

    var executor = Executors.newFixedThreadPool(2);
    try (var blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      blocker.createStatement().execute("SELECT pg_advisory_xact_lock(918273645)");
      CompletableFuture<Void> assignment = CompletableFuture.runAsync(() -> projections.upsert(
          new ProjectionReadModel.ResourceAssignment(
              UUID.randomUUID(), executionId, appointmentId, null, cabinId, tankId,
              "INITIAL", completedAt.minusSeconds(600), null, UUID.randomUUID(), 4,
              completedAt.minusSeconds(600))), executor);
      awaitAdvisoryWaiters(1);

      CompletableFuture<Void> completion = CompletableFuture.runAsync(() ->
          new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            projections.upsert(new ProjectionReadModel.WashExecution(
                executionId, appointmentId, "NORMAL", "COMPLETED", null, null, null, null, completedAt,
                null, null, null, null, null, null, null, 6, completedAt));
            completions.projectCompletion(executionId, 5, 6, 7, 8, 6, completedAt);
          }), executor);
      awaitAdvisoryWaiters(2);

      blocker.commit();
      CompletableFuture.allOf(assignment, completion).get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      jdbc.sql("DROP TRIGGER IF EXISTS block_test_assignment_before_insert ON wash_resource_assignment_read_model")
          .update();
      jdbc.sql("DROP FUNCTION IF EXISTS block_test_assignment()")
          .update();
    }

    var assignment = jdbc.sql("""
        SELECT released_at FROM wash_resource_assignment_read_model
        WHERE wash_execution_id = :executionId
        """).param("executionId", executionId).query().singleRow();
    assertThat(((java.sql.Timestamp) assignment.get("released_at")).toInstant()).isEqualTo(completedAt);
  }

  private void awaitAdvisoryWaiters(long expected) throws InterruptedException {
    for (int attempt = 0; attempt < 250; attempt++) {
      long waiters = jdbc.sql("""
          SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted
          """).query(Long.class).single();
      if (waiters >= expected) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Expected at least " + expected + " advisory-lock waiters");
  }

  @Test
  void supervisorHomeCountsDistinctAppointmentsForTheServiceDateAndTheExistingPendingQueue() {
    UUID scheduled = appointment(UUID.randomUUID(), "SCHEDULED", Instant.parse("2026-08-01T08:00:00Z"));
    UUID inProgress = appointment(UUID.randomUUID(), "IN_PROGRESS", Instant.parse("2026-08-01T09:00:00Z"));
    appointment(UUID.randomUUID(), "COMPLETED", Instant.parse("2026-08-01T10:00:00Z"));
    appointment(UUID.randomUUID(), "ENTRY_REJECTED", Instant.parse("2026-08-01T11:00:00Z"));
    appointment(UUID.randomUUID(), "CANCELLED", Instant.parse("2026-08-01T12:00:00Z"));
    appointment(UUID.randomUUID(), "MISSED", Instant.parse("2026-08-01T13:00:00Z"));
    insertExecution(scheduled, "PENDING_ENTRY", Instant.parse("2026-08-01T08:05:00Z"));
    insertExecution(inProgress, "PENDING_REASSIGNMENT", Instant.parse("2026-08-01T09:05:00Z"));
    appointment(UUID.randomUUID(), "COMPLETED", Instant.parse("2026-08-02T10:00:00Z"),
        java.time.LocalDate.of(2026, 8, 2));

    var snapshot = supervision.summarizeHome(java.time.LocalDate.of(2026, 8, 1));

    assertThat(snapshot.registeredAppointments()).isEqualTo(6);
    assertThat(snapshot.inProcessAppointments()).isEqualTo(2);
    assertThat(snapshot.completedAppointments()).isEqualTo(1);
    assertThat(snapshot.deniedAppointments()).isEqualTo(1);
    assertThat(snapshot.cancelledAppointments()).isEqualTo(1);
    assertThat(snapshot.pendingReassignmentsCount()).isEqualTo(1);
  }

  @Test
  void supervisorLookupCandidatesReturnAllAppointmentsInDeterministicLatestFirstOrder() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    Instant old = Instant.parse("2026-08-01T08:00:00Z");
    Instant latest = Instant.parse("2026-08-01T10:00:00Z");
    jdbc.sql("""
        INSERT INTO person_presentation_read_model (
          person_id, first_name, paternal_surname, maternal_surname, status, source_version, updated_at)
        VALUES (:personId, 'Ana', 'García', 'Reyes', 'ACTIVE', 1, :updatedAt)
        """).param("personId", personId).param("updatedAt", java.sql.Timestamp.from(latest)).update();
    jdbc.sql("""
        INSERT INTO student_academic_profile_read_model (
          student_academic_profile_id, person_id, account_id, student_number, current_semester,
          academic_status, source_version, updated_at)
        VALUES (:profileId, :personId, :accountId, '201945678', 7, 'ACTIVE', 1, :updatedAt)
        """).param("profileId", UUID.randomUUID()).param("personId", personId)
        .param("accountId", accountId).param("updatedAt", java.sql.Timestamp.from(latest)).update();
    UUID completed = appointment(accountId, "COMPLETED", old);
    UUID active = appointment(accountId, "IN_PROGRESS", latest);
    jdbc.sql("""
        UPDATE appointment_read_model
        SET appointment_type = 'NORMAL', instrument_count = 15, piece_type = 'HIGH_SPEED'
        WHERE appointment_id IN (:completed, :active)
        """).param("completed", completed).param("active", active).update();
    UUID executionId = insertExecution(active, "EXIT_SUBMITTED", latest.plusSeconds(60));
    exitMaterials.projectSubmittedExitMaterials(executionId, 1, 2, 3, 4, 2, latest.plusSeconds(90));

    var candidates = supervision.findAppointments(accountId, java.time.LocalDate.of(2026, 8, 1));

    assertThat(candidates).extracting(item -> item.appointmentId()).containsExactly(active, completed);
    var selected = candidates.getFirst();
    assertThat(selected.firstName()).isEqualTo("Ana");
    assertThat(selected.studentNumber()).isEqualTo("201945678");
    assertThat(selected.executionStatus()).isEqualTo("EXIT_SUBMITTED");
    assertThat(selected.exitSubmittedAt()).isEqualTo(latest.plusSeconds(60));
    assertThat(selected.submittedWitnessTapePortionCount()).isEqualTo(4);
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
    return appointment(accountId, status, updatedAt, java.time.LocalDate.of(2026, 8, 1));
  }

  private UUID appointment(UUID accountId, String status, Instant updatedAt, java.time.LocalDate serviceDate) {
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO appointment_time_slot_read_model (
          appointment_time_slot_id, service_date, starts_at, ends_at, timezone, status, updated_at)
        VALUES (:id, :serviceDate, :startsAt, :endsAt, 'America/Mexico_City', 'ACTIVE', :updatedAt)
        """)
        .param("id", slotId)
        .param("serviceDate", serviceDate)
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

  private UUID insertExecution(UUID appointmentId, String status, Instant updatedAt) {
    UUID executionId = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO wash_execution_read_model (
          wash_execution_id, appointment_id, status, arrived_at, exit_submitted_at,
          execution_version, updated_at)
        VALUES (:executionId, :appointmentId, :status, :arrivedAt,
          CASE WHEN :status = 'EXIT_SUBMITTED'
            THEN CAST(:updatedAt AS TIMESTAMPTZ)
            ELSE NULL::TIMESTAMPTZ
          END, 2, :updatedAt)
        """).param("executionId", executionId).param("appointmentId", appointmentId)
        .param("status", status).param("arrivedAt", java.sql.Timestamp.from(updatedAt.minusSeconds(30)))
        .param("updatedAt", java.sql.Timestamp.from(updatedAt)).update();
    return executionId;
  }
}
