package com.estoma.washexecution.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.command.CommandMetadata;
import com.estoma.washexecution.application.command.CommandOutcome;
import com.estoma.washexecution.application.command.CommandRequest;
import com.estoma.washexecution.application.command.WashExecutionCommand;
import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.OptimisticConcurrencyException;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.WashExecutionCommandUseCase;
import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.AssignmentType;
import com.estoma.washexecution.domain.execution.ExitMaterials;
import com.estoma.washexecution.domain.execution.ResourceAssignment;
import com.estoma.washexecution.domain.execution.WashExecution;
import com.estoma.washexecution.domain.execution.WashExecutionStatus;
import com.estoma.washexecution.infrastructure.messaging.inbound.TransientFailureClassifier;
import com.estoma.washexecution.infrastructure.persistence.jdbc.JdbcTemporal;
import com.estoma.washexecution.infrastructure.persistence.jdbc.JdbcWashExecutionRepository;
import com.estoma.washexecution.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "estoma.completion.direct-supervisor-enabled=true")
class DirectSupervisorCompletionIT extends AbstractPostgresIT {
  private static final Instant BASE = Instant.parse("2026-08-23T10:00:00Z");

  @Autowired WashExecutionCommandUseCase commands;
  @Autowired WashExecutionRepository executions;
  @Autowired ResourceAssignmentRepository assignments;
  @Autowired CleaningPeriodRepository cleanings;
  @Autowired TransientFailureClassifier transientFailures;
  @Autowired RaceWashExecutionRepository raceExecutions;

  @TestConfiguration
  static class RaceConfiguration {
    @Bean
    @Primary
    RaceWashExecutionRepository raceWashExecutionRepository(
        JdbcWashExecutionRepository delegate) {
      return new RaceWashExecutionRepository(delegate);
    }
  }

  @BeforeEach
  void seedReadiness() {
    raceExecutions.reset();
    ready("authorization", "authorization");
    ready("appointments", "appointments");
  }

  @Test
  void directCompletionPersistsAuditAndFinalsWithoutInventingStudentSubmission() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID executionId = createInProgress(UUID.randomUUID(), AppointmentType.IMMUNOCOMPROMISED);
    ResourceAssignment assignment = assign(executionId);
    int version = executions.findById(executionId).orElseThrow().version();
    CommandRequest request = request(supervisor, "direct-completion",
        new WashExecutionCommand.CompleteWashExecution(
            executionId, supervisor, new ExitMaterials(0, 2, 0, 1),
            BASE.plusSeconds(20 * 60L), version));

    assertThat(commands.dispatch(request).status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    WashExecution completed = executions.findById(executionId).orElseThrow();
    assertThat(completed.status()).isEqualTo(WashExecutionStatus.COMPLETED);
    assertThat(completed.version()).isEqualTo(version + 1);
    assertThat(completed.completedByAccountId()).isEqualTo(supervisor);
    assertThat(completed.completedAt()).isEqualTo(BASE.plusSeconds(20 * 60L));
    assertThat(completed.exitMaterials()).isEqualTo(new ExitMaterials(0, 2, 0, 1));
    assertThat(completed.exitSubmittedByAccountId()).isNull();
    assertThat(completed.exitSubmittedAt()).isNull();
    assertThat(assignments.findActiveByExecutionId(executionId)).isEmpty();
    assertThat(cleanings.findByAssignmentId(assignment.resourceAssignmentId())).isPresent();
    assertThat(eventCount(executionId, "WashExecutionCompleted")).isEqualTo(1);
    assertThat(eventCount(executionId, "WashExitSubmitted")).isZero();
    assertThat(jdbc.queryForObject(
        "SELECT payload_json FROM outbox_messages WHERE aggregate_id=? AND message_type='WashExecutionCompleted'",
        String.class, executionId)).doesNotContain("exitSubmitted", "submittedByStudent");

    assertThat(commands.dispatch(request).status()).isEqualTo(CommandOutcome.Status.DUPLICATE);
    assertThat(executions.findById(executionId).orElseThrow().version()).isEqualTo(version + 1);
    assertThat(eventCount(executionId, "WashExecutionCompleted")).isEqualTo(1);
  }

  @Test
  void completionGuardsActorVersionAndActiveAssignmentBeforeMutation() {
    UUID supervisor = UUID.randomUUID();
    UUID otherSupervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    authorize(otherSupervisor, "SUPERVISOR_LAVADO");
    UUID executionId = createInProgress(UUID.randomUUID(), AppointmentType.NORMAL);
    int version = executions.findById(executionId).orElseThrow().version();
    ExitMaterials materials = new ExitMaterials(1, 0, 0, 0);

    assertThat(dispatch(otherSupervisor, "wrong-actor",
        new WashExecutionCommand.CompleteWashExecution(
            executionId, supervisor, materials, BASE.plusSeconds(1200), version)).errorCode())
        .isEqualTo("ACTOR_NOT_AUTHORIZED");
    assertThat(dispatch(supervisor, "stale-version",
        new WashExecutionCommand.CompleteWashExecution(
            executionId, supervisor, materials, BASE.plusSeconds(1200), version - 1)).errorCode())
        .isEqualTo("VERSION_CONFLICT");
    assertThat(dispatch(supervisor, "no-assignment",
        new WashExecutionCommand.CompleteWashExecution(
            executionId, supervisor, materials, BASE.plusSeconds(1200), version)).errorCode())
        .isEqualTo("ACTIVE_RESOURCE_ASSIGNMENT_NOT_FOUND");

    WashExecution unchanged = executions.findById(executionId).orElseThrow();
    assertThat(unchanged.status()).isEqualTo(WashExecutionStatus.IN_PROGRESS);
    assertThat(unchanged.version()).isEqualTo(version);
    assertThat(eventCount(executionId, "WashExecutionCompleted")).isZero();
  }

  @Test
  void studentExitWinningCasRollsBackCompetingDirectCompletion() throws Exception {
    runDeterministicRace(RaceWinner.STUDENT);
  }

  @Test
  void directCompletionWinningCasRollsBackCompetingStudentExit() throws Exception {
    runDeterministicRace(RaceWinner.SUPERVISOR);
  }

  private void runDeterministicRace(RaceWinner winner) throws Exception {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    authorize(student, "ALUMNO");
    UUID slot = seedSlot();
    UUID appointment = seedAppointment(student, slot);
    UUID executionId = createInProgress(appointment, AppointmentType.IMMUNOCOMPROMISED);
    ResourceAssignment assignment = assign(executionId);
    int version = executions.findById(executionId).orElseThrow().version();
    raceExecutions.arm(winner, version);

    CommandRequest submitRequest = request(student, "race-submit",
        new WashExecutionCommand.SubmitWashExit(
            executionId, student, new ExitMaterials(1, 0, 0, 0),
            BASE.plusSeconds(1100), version));
    CommandRequest completeRequest = request(supervisor, "race-complete",
        new WashExecutionCommand.CompleteWashExecution(
            executionId, supervisor, new ExitMaterials(0, 1, 0, 0),
            BASE.plusSeconds(1200), version));

    Object submitResult;
    Object completeResult;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var submit = pool.submit(() -> dispatchCapturingFailure(submitRequest));
      var complete = pool.submit(() -> dispatchCapturingFailure(completeRequest));
      submitResult = submit.get(10, TimeUnit.SECONDS);
      completeResult = complete.get(10, TimeUnit.SECONDS);
    }

    Object winningResult = winner == RaceWinner.STUDENT ? submitResult : completeResult;
    Object losingResult = winner == RaceWinner.STUDENT ? completeResult : submitResult;
    CommandRequest losingRequest = winner == RaceWinner.STUDENT ? completeRequest : submitRequest;
    assertThat(winningResult).isInstanceOf(CommandOutcome.class);
    assertThat(((CommandOutcome) winningResult).status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(losingResult).isInstanceOf(OptimisticConcurrencyException.class);
    assertThat(transientFailures.isTransient((Throwable) losingResult)).isTrue();
    CommandOutcome redelivered = commands.dispatch(losingRequest);
    assertThat(redelivered.status()).isEqualTo(CommandOutcome.Status.REJECTED);
    assertThat(redelivered.errorCode()).isEqualTo("VERSION_CONFLICT");

    WashExecution persisted = executions.findById(executionId).orElseThrow();
    assertThat(persisted.version()).isEqualTo(version + 1);
    assertThat(eventCount(executionId, "WashExitSubmitted")
        + eventCount(executionId, "WashExecutionCompleted")).isEqualTo(1);
    if (winner == RaceWinner.STUDENT) {
      assertThat(persisted.status()).isEqualTo(WashExecutionStatus.EXIT_SUBMITTED);
      assertThat(persisted.exitMaterials()).isEqualTo(new ExitMaterials(1, 0, 0, 0));
      assertThat(assignments.findActiveByExecutionId(executionId)).isPresent();
      assertThat(cleanings.findByAssignmentId(assignment.resourceAssignmentId())).isEmpty();
      assertThat(eventCount(executionId, "WashExecutionCompleted")).isZero();
      assertThat(eventCount(executionId, "CleaningPeriodStarted")).isZero();
    } else {
      assertThat(persisted.status()).isEqualTo(WashExecutionStatus.COMPLETED);
      assertThat(persisted.exitMaterials()).isEqualTo(new ExitMaterials(0, 1, 0, 0));
      assertThat(persisted.exitSubmittedAt()).isNull();
      assertThat(assignments.findActiveByExecutionId(executionId)).isEmpty();
      assertThat(cleanings.findByAssignmentId(assignment.resourceAssignmentId())).isPresent();
      assertThat(eventCount(executionId, "WashExitSubmitted")).isZero();
    }
  }

  @Test
  void staleDirectCompletionCannotOverwriteStudentMaterials() {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    authorize(student, "ALUMNO");
    UUID appointment = seedAppointment(student, seedSlot());
    UUID executionId = createInProgress(appointment, AppointmentType.NORMAL);
    assign(executionId);
    int version = executions.findById(executionId).orElseThrow().version();

    assertThat(dispatch(student, "student-first", new WashExecutionCommand.SubmitWashExit(
        executionId, student, new ExitMaterials(2, 0, 0, 0),
        BASE.plusSeconds(1100), version)).status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    CommandOutcome stale = dispatch(supervisor, "direct-second",
        new WashExecutionCommand.CompleteWashExecution(
            executionId, supervisor, new ExitMaterials(0, 2, 0, 0),
            BASE.plusSeconds(1200), version));

    assertThat(stale.errorCode()).isEqualTo("VERSION_CONFLICT");
    WashExecution persisted = executions.findById(executionId).orElseThrow();
    assertThat(persisted.status()).isEqualTo(WashExecutionStatus.EXIT_SUBMITTED);
    assertThat(persisted.exitMaterials()).isEqualTo(new ExitMaterials(2, 0, 0, 0));
    assertThat(assignments.findActiveByExecutionId(executionId)).isPresent();
    assertThat(eventCount(executionId, "WashExecutionCompleted")).isZero();
  }

  @Test
  void staleStudentExitCannotReopenDirectlyCompletedExecution() {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    authorize(student, "ALUMNO");
    UUID appointment = seedAppointment(student, seedSlot());
    UUID executionId = createInProgress(appointment, AppointmentType.NORMAL);
    assign(executionId);
    int version = executions.findById(executionId).orElseThrow().version();

    assertThat(dispatch(supervisor, "direct-first", new WashExecutionCommand.CompleteWashExecution(
        executionId, supervisor, new ExitMaterials(0, 2, 0, 0),
        BASE.plusSeconds(1200), version)).status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    CommandOutcome stale = dispatch(student, "student-second",
        new WashExecutionCommand.SubmitWashExit(
            executionId, student, new ExitMaterials(2, 0, 0, 0),
            BASE.plusSeconds(1100), version));

    assertThat(stale.errorCode()).isEqualTo("VERSION_CONFLICT");
    WashExecution persisted = executions.findById(executionId).orElseThrow();
    assertThat(persisted.status()).isEqualTo(WashExecutionStatus.COMPLETED);
    assertThat(persisted.exitMaterials()).isEqualTo(new ExitMaterials(0, 2, 0, 0));
    assertThat(persisted.exitSubmittedAt()).isNull();
    assertThat(assignments.findActiveByExecutionId(executionId)).isEmpty();
    assertThat(eventCount(executionId, "WashExitSubmitted")).isZero();
  }

  private Object dispatchCapturingFailure(CommandRequest request) {
    try {
      return commands.dispatch(request);
    } catch (Throwable error) {
      return error;
    }
  }

  private CommandOutcome dispatch(UUID actor, String key, WashExecutionCommand command) {
    return commands.dispatch(request(actor, key, command));
  }

  private CommandRequest request(UUID actor, String key, WashExecutionCommand command) {
    return new CommandRequest(new CommandMetadata(
        UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
        "platform-bff", key, ActorContext.account(actor)), command);
  }

  private UUID createInProgress(UUID appointmentId, AppointmentType type) {
    UUID id = UUID.randomUUID();
    WashExecution execution = WashExecution.register(id, appointmentId, type, BASE, BASE);
    executions.save(execution, 0);
    execution.start(BASE.plusSeconds(10));
    executions.save(execution, 1);
    return id;
  }

  private ResourceAssignment assign(UUID executionId) {
    ResourceAssignment assignment = new ResourceAssignment(
        UUID.randomUUID(), executionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        AssignmentType.INITIAL, null, BASE.plusSeconds(60));
    assignments.save(assignment);
    return assignment;
  }

  private int eventCount(UUID executionId, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox_messages WHERE aggregate_id=? AND message_type=?",
        Integer.class, executionId, type);
  }

  private void ready(String projection, String source) {
    jdbc.update("""
        INSERT INTO projection_recovery_state(
          projection_name,source_logical_component_id,readiness_status,
          last_successful_rebuild_at,last_error,updated_at)
        VALUES(?,?,'READY',now(),NULL,now())
        """, projection, source);
  }

  private void authorize(UUID accountId, String role) {
    jdbc.update("""
        INSERT INTO authorization_role_projection(account_id,role_code,role_source_version,updated_at)
        VALUES(?,?,1,now())
        """, accountId, role);
    jdbc.update("""
        INSERT INTO authorization_access_projection(
          account_id,system_id,system_code,access_status,access_source_version,updated_at)
        VALUES(?,?,'LAVADO_ULTRASONICO','ACTIVE',1,now())
        """, accountId, UUID.randomUUID());
  }

  private UUID seedSlot() {
    UUID slot = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO appointment_time_slot_projection(
          appointment_time_slot_id,calendar_id,service_date,starts_at,ends_at,timezone,
          arrival_tolerance_until,status,source_version,updated_at)
        VALUES(?,?,?, ?,?,'UTC',?,'ACTIVE',1,now())
        """, slot, UUID.randomUUID(), LocalDate.of(2026, 8, 23),
        JdbcTemporal.timestamp(BASE.plusSeconds(900)),
        JdbcTemporal.timestamp(BASE.plusSeconds(4500)),
        JdbcTemporal.timestamp(BASE.plusSeconds(1500)));
    return slot;
  }

  private UUID seedAppointment(UUID student, UUID slot) {
    UUID appointment = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO appointment_projection(
          appointment_id,student_account_id,appointment_time_slot_id,course_section_id,
          appointment_type,instrument_count,piece_type,appointment_status,entry_authorized_at,
          source_version,updated_at)
        VALUES(?,?,?,?, 'NORMAL',1,'INSTRUMENT','IN_PROGRESS',NULL,1,now())
        """, appointment, student, slot, UUID.randomUUID());
    return appointment;
  }

  enum RaceWinner { STUDENT, SUPERVISOR }

  static final class RaceWashExecutionRepository implements WashExecutionRepository {
    private final JdbcWashExecutionRepository delegate;
    private volatile Race race;

    RaceWashExecutionRepository(JdbcWashExecutionRepository delegate) {
      this.delegate = delegate;
    }

    void arm(RaceWinner winner, int expectedVersion) {
      race = new Race(winner, expectedVersion);
    }

    void reset() {
      race = null;
    }

    @Override
    public Optional<WashExecution> findById(UUID id) {
      return delegate.findById(id);
    }

    @Override
    public Optional<WashExecution> findByAppointmentId(UUID appointmentId) {
      return delegate.findByAppointmentId(appointmentId);
    }

    @Override
    public void save(WashExecution execution, int expectedVersion) {
      Race current = race;
      boolean competingCompletion = execution.status() == WashExecutionStatus.COMPLETED;
      boolean competingSubmission = execution.status() == WashExecutionStatus.EXIT_SUBMITTED;
      if (current == null || expectedVersion != current.expectedVersion
          || (!competingCompletion && !competingSubmission)) {
        delegate.save(execution, expectedVersion);
        return;
      }

      current.ready.countDown();
      await(current.ready);
      boolean selectedWinner = competingSubmission
          ? current.winner == RaceWinner.STUDENT
          : current.winner == RaceWinner.SUPERVISOR;
      if (selectedWinner) {
        delegate.save(execution, expectedVersion);
        current.winnerSaved.countDown();
        return;
      }
      await(current.winnerSaved);
      delegate.save(execution, expectedVersion);
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("race coordination timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }

    static final class Race {
      final RaceWinner winner;
      final int expectedVersion;
      final CountDownLatch ready = new CountDownLatch(2);
      final CountDownLatch winnerSaved = new CountDownLatch(1);

      Race(RaceWinner winner, int expectedVersion) {
        this.winner = winner;
        this.expectedVersion = expectedVersion;
      }
    }
  }
}
