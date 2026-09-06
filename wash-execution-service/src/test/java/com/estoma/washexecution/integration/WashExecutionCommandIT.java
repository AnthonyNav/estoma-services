package com.estoma.washexecution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.command.CommandMetadata;
import com.estoma.washexecution.application.command.CommandOutcome;
import com.estoma.washexecution.application.command.CommandRequest;
import com.estoma.washexecution.application.command.WashExecutionCommand;
import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.ResourceUnavailabilityRepository;
import com.estoma.washexecution.application.port.WashExecutionCommandUseCase;
import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.AssignmentType;
import com.estoma.washexecution.domain.execution.CleaningPeriod;
import com.estoma.washexecution.domain.execution.CleaningTrigger;
import com.estoma.washexecution.domain.execution.ReleaseReason;
import com.estoma.washexecution.domain.execution.EntryDecision;
import com.estoma.washexecution.domain.execution.ExitMaterials;
import com.estoma.washexecution.domain.execution.ResourceAssignment;
import com.estoma.washexecution.domain.execution.UnavailabilityCauseType;
import com.estoma.washexecution.domain.execution.WashExecution;
import com.estoma.washexecution.domain.execution.WashExecutionStatus;
import com.estoma.washexecution.infrastructure.persistence.jdbc.JdbcTemporal;
import com.estoma.washexecution.infrastructure.messaging.inbound.TransientFailureClassifier;
import com.estoma.washexecution.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WashExecutionCommandIT extends AbstractPostgresIT {
  private static final Instant BASE = Instant.parse("2026-08-23T10:00:00Z");
  private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 23);

  @Autowired WashExecutionCommandUseCase commands;
  @Autowired WashExecutionRepository executions;
  @Autowired ResourceAssignmentRepository assignments;
  @Autowired ResourceUnavailabilityRepository unavailabilities;
  @Autowired CleaningPeriodRepository cleanings;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DecisionClock decisionClock;

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    DecisionClock decisionClock() { return new DecisionClock(); }
  }

  static final class DecisionClock extends Clock {
    private Instant now = BASE;
    void advanceTo(Instant value) { now = value; }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
  }

  @BeforeEach
  void seedReadiness() {
    decisionClock.advanceTo(BASE.plusSeconds(1500));
    ready("appointments", "appointments");
    ready("wash-administration", "wash-administration");
    ready("authorization", "authorization");
  }

  @Test
  void registerAndAuthorizePacksPartiallyUsedPreferredTank() {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");

    UUID calendar = UUID.randomUUID();
    UUID slot = seedSlot(calendar, BASE.plusSeconds(15 * 60L));
    UUID appointment = seedAppointment(student, slot, AppointmentType.NORMAL, "SCHEDULED");
    UUID cabin = UUID.randomUUID();
    UUID partialTank = UUID.randomUUID();
    UUID emptyTank = UUID.randomUUID();
    seedCabin(cabin, "C-NORMAL", "NORMAL");
    seedTank(partialTank, cabin, "T-1", 2);
    seedTank(emptyTank, cabin, "T-2", 2);

    UUID existingExecution = createInProgress(UUID.randomUUID(), AppointmentType.NORMAL, BASE.plusSeconds(30));
    assignments.save(new ResourceAssignment(
        UUID.randomUUID(), existingExecution, slot, cabin, partialTank,
        AssignmentType.INITIAL, null, BASE.plusSeconds(60)));

    UUID executionId = UUID.randomUUID();
    CommandOutcome arrival = dispatch(
        supervisor,
        "arrival-normal",
        new WashExecutionCommand.RegisterWashArrival(executionId, appointment, BASE));
    assertThat(arrival.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.PENDING_ENTRY);

    Instant decidedAt = BASE.plusSeconds(10 * 60L);
    CommandOutcome decision = dispatch(
        supervisor,
        "decide-normal",
        new WashExecutionCommand.DecideWashEntry(
            executionId, true, true, EntryDecision.AUTHORIZED, null,
            supervisor, decidedAt, 1));

    assertThat(decision.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.IN_PROGRESS);
    assertThat(assignments.findActiveByExecutionId(executionId).orElseThrow().tankId())
        .isEqualTo(partialTank);
    assertThat(outboxTypes()).contains(
        "RegisterWashArrivalSucceeded",
        "WashArrivalRegistered",
        "DecideWashEntrySucceeded",
        "WashEntryAuthorized",
        "WashResourceAssigned",
        "WashExecutionStarted");
  }

  @Test
  void authorizedWithoutResourceThenResolveCreatesInitialAssignmentAndFirstStart() {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID calendar = UUID.randomUUID();
    UUID slot = seedSlot(calendar, BASE.plusSeconds(15 * 60L));
    UUID appointment = seedAppointment(student, slot, AppointmentType.JOURNEY, "SCHEDULED");
    UUID executionId = UUID.randomUUID();

    assertThat(dispatch(supervisor, "arrival-pending",
        new WashExecutionCommand.RegisterWashArrival(executionId, appointment, BASE)).status())
        .isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(dispatch(supervisor, "decide-pending",
        new WashExecutionCommand.DecideWashEntry(
            executionId, true, true, EntryDecision.AUTHORIZED, null,
            supervisor, BASE.plusSeconds(5 * 60L), 1)).status())
        .isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.PENDING_REASSIGNMENT);

    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    seedCabin(cabin, "C-JOURNEY", "JOURNEY");
    seedTank(tank, cabin, "T-JOURNEY", 1);

    CommandOutcome resolved = dispatch(supervisor, "resolve-initial",
        new WashExecutionCommand.ResolvePendingReassignment(
            executionId, cabin, tank, supervisor, BASE.plusSeconds(6 * 60L), 2));

    assertThat(resolved.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    ResourceAssignment assignment = assignments.findActiveByExecutionId(executionId).orElseThrow();
    assertThat(assignment.assignmentType()).isEqualTo(AssignmentType.INITIAL);
    assertThat(assignment.assignedByAccountId()).isEqualTo(supervisor);
    assertThat(executions.findById(executionId).orElseThrow().startedAt()).isNotNull();
    assertThat(outboxTypes()).contains("WashResourceAssigned", "WashExecutionStarted");
    assertThat(outboxTypes()).doesNotContain("WashResourceReassigned");
  }

  @Test
  void rejectEntryIsSuccessfulCommandButTerminalBusinessDecision() {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(15 * 60L));
    UUID appointment = seedAppointment(student, slot, AppointmentType.NORMAL, "SCHEDULED");
    UUID executionId = UUID.randomUUID();

    dispatch(supervisor, "arrival-rejected",
        new WashExecutionCommand.RegisterWashArrival(executionId, appointment, BASE));
    CommandOutcome outcome = dispatch(supervisor, "decide-rejected",
        new WashExecutionCommand.DecideWashEntry(
            executionId, false, false, EntryDecision.REJECTED, "Identity mismatch",
            supervisor, BASE.plusSeconds(5 * 60L), 1));

    assertThat(outcome.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.ENTRY_REJECTED);
    assertThat(outboxTypes()).contains("DecideWashEntrySucceeded", "WashEntryRejected");
    assertThat(outboxTypes()).doesNotContain("DecideWashEntryRejected");
  }

  @Test
  void disablePreservesAllAffectedAssignmentsThenRestoreOnlyRestoresAvailability() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    seedCabin(cabin, "C-FAIL", "NORMAL");
    seedTank(tank, cabin, "T-FAIL", 2);

    UUID executionA = createInProgress(UUID.randomUUID(), AppointmentType.NORMAL, BASE);
    UUID executionB = createInProgress(UUID.randomUUID(), AppointmentType.NORMAL, BASE.plusSeconds(60));
    UUID assignmentA = UUID.randomUUID();
    UUID assignmentB = UUID.randomUUID();
    assignments.save(new ResourceAssignment(
        assignmentA, executionA, UUID.randomUUID(), cabin, tank,
        AssignmentType.INITIAL, null, BASE.plusSeconds(120)));
    assignments.save(new ResourceAssignment(
        assignmentB, executionB, UUID.randomUUID(), cabin, tank,
        AssignmentType.INITIAL, null, BASE.plusSeconds(180)));

    UUID unavailableId = UUID.randomUUID();
    CommandOutcome disabled = dispatch(supervisor, "disable-tank",
        new WashExecutionCommand.DisableOperationalResource(
            unavailableId, null, tank, UnavailabilityCauseType.FAILURE,
            "Pump failure", supervisor, executionA, BASE.plusSeconds(240)));

    assertThat(disabled.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(executions.findById(executionA).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.PENDING_REASSIGNMENT);
    assertThat(executions.findById(executionB).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.PENDING_REASSIGNMENT);
    assertThat(assignments.findActiveByExecutionId(executionA)).isPresent();
    assertThat(assignments.findActiveByExecutionId(executionB)).isPresent();
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM resource_unavailability_affected_executions WHERE resource_unavailability_id=?",
        Integer.class, unavailableId)).isEqualTo(2);
    assertThat(outboxTypes().stream().filter("WashExecutionPendingReassignment"::equals).count())
        .isEqualTo(2);

    CommandOutcome restored = dispatch(supervisor, "restore-tank",
        new WashExecutionCommand.RestoreOperationalResource(
            unavailableId, null, supervisor, BASE.plusSeconds(300), 1));
    assertThat(restored.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(unavailabilities.findById(unavailableId).orElseThrow().active()).isFalse();
    assertThat(unavailabilities.findById(unavailableId).orElseThrow().version()).isEqualTo(2);
    assertThat(executions.findById(executionA).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.PENDING_REASSIGNMENT);
  }

  @Test
  void submitExitRequiresOwnerStudentAndPayloadActorMatch() {
    UUID owner = UUID.randomUUID();
    UUID otherStudent = UUID.randomUUID();
    authorize(owner, "ALUMNO");
    authorize(otherStudent, "ALUMNO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(15 * 60L));
    UUID appointment = seedAppointment(owner, slot, AppointmentType.NORMAL, "IN_PROGRESS");
    UUID executionId = createInProgress(appointment, AppointmentType.NORMAL, BASE);
    int version = executions.findById(executionId).orElseThrow().version();

    CommandOutcome denied = dispatch(otherStudent, "exit-wrong-owner",
        new WashExecutionCommand.SubmitWashExit(
            executionId, owner, new ExitMaterials(1, 0, 0, 0), BASE.plusSeconds(20 * 60L), version));
    assertThat(denied.status()).isEqualTo(CommandOutcome.Status.REJECTED);
    assertThat(denied.errorCode()).isEqualTo("ACTOR_NOT_AUTHORIZED");
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.IN_PROGRESS);

    CommandOutcome submitted = dispatch(owner, "exit-owner",
        new WashExecutionCommand.SubmitWashExit(
            executionId, owner, new ExitMaterials(1, 0, 1, 0), BASE.plusSeconds(20 * 60L), version));
    assertThat(submitted.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.EXIT_SUBMITTED);
    assertThat(outboxTypes()).contains("WashExitSubmitted");
  }

  @Test
  void completeImmunocompromisedReleasesAssignmentAndCreatesThreeHourCleaning() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID appointment = UUID.randomUUID();
    UUID executionId = createInProgress(appointment, AppointmentType.IMMUNOCOMPROMISED, BASE);
    WashExecution execution = executions.findById(executionId).orElseThrow();
    execution.submitExit(UUID.randomUUID(), new ExitMaterials(1, 0, 0, 0), BASE.plusSeconds(20 * 60L));
    executions.save(execution, execution.version() - 1);
    int expectedVersion = execution.version();

    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    ResourceAssignment assignment = new ResourceAssignment(
        UUID.randomUUID(), executionId, UUID.randomUUID(), cabin, tank,
        AssignmentType.INITIAL, null, BASE.plusSeconds(60));
    assignments.save(assignment);
    Instant completedAt = BASE.plusSeconds(30 * 60L);

    CommandOutcome completed = dispatch(supervisor, "complete-immuno",
        new WashExecutionCommand.CompleteWashExecution(
            executionId, supervisor, new ExitMaterials(0, 1, 1, 0), completedAt, expectedVersion));

    assertThat(completed.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.COMPLETED);
    assertThat(assignments.findActiveByExecutionId(executionId)).isEmpty();
    var cleaning = cleanings.findByAssignmentId(assignment.resourceAssignmentId()).orElseThrow();
    assertThat(cleaning.startsAt()).isEqualTo(completedAt);
    assertThat(cleaning.expectedEndsAt()).isEqualTo(completedAt.plusSeconds(3 * 60 * 60L));
    assertThat(outboxTypes()).contains("WashExecutionCompleted", "CleaningPeriodStarted");
  }

  @Test
  void cancelAfterPhysicalImmunoUseCreatesCleaningButNeverCancelsDirectlyFromInProgress() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(15 * 60L));
    UUID appointment = seedAppointment(UUID.randomUUID(), slot, AppointmentType.IMMUNOCOMPROMISED, "IN_PROGRESS");
    UUID executionId = createInProgress(appointment, AppointmentType.IMMUNOCOMPROMISED, BASE);
    WashExecution pending = executions.findById(executionId).orElseThrow();
    pending.loseResource();
    executions.save(pending, pending.version() - 1);
    int expectedVersion = pending.version();

    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    ResourceAssignment assignment = new ResourceAssignment(
        UUID.randomUUID(), executionId, UUID.randomUUID(), cabin, tank,
        AssignmentType.INITIAL, null, BASE.plusSeconds(60));
    assignments.save(assignment);
    Instant cancelledAt = BASE.plusSeconds(25 * 60L);

    CommandOutcome cancelled = dispatch(supervisor, "cancel-immuno",
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "CAPACITY_LOSS", null, supervisor, cancelledAt, expectedVersion));

    assertThat(cancelled.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.CANCELLED);
    assertThat(assignments.findActiveByExecutionId(executionId)).isEmpty();
    assertThat(cleanings.findByAssignmentId(assignment.resourceAssignmentId())).isPresent();
    assertThat(outboxTypes()).contains("WashExecutionCancelled", "CleaningPeriodStarted");
  }

  @Test
  void capacityLossRejectsAvailableAlternativeWithoutMutatingExecutionOrAssignment() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(15 * 60L));
    UUID appointment = seedAppointment(UUID.randomUUID(), slot, AppointmentType.NORMAL, "IN_PROGRESS");
    UUID executionId = createPending(appointment);
    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    seedCabin(cabin, "C-ALTERNATIVE", "NORMAL");
    seedTank(tank, cabin, "T-ALTERNATIVE", 2);
    ResourceAssignment assignment = new ResourceAssignment(
        UUID.randomUUID(), executionId, slot, cabin, tank, AssignmentType.INITIAL, null, BASE);
    assignments.save(assignment);
    int version = executions.findById(executionId).orElseThrow().version();

    CommandOutcome result = dispatch(supervisor, "capacity-available",
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "CAPACITY_LOSS", null, supervisor, BASE.plusSeconds(1500), version));

    assertThat(result.errorCode()).isEqualTo("CAPACITY_ALTERNATIVE_AVAILABLE");
    assertThat(result.status()).isEqualTo(CommandOutcome.Status.REJECTED);
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.PENDING_REASSIGNMENT);
    assertThat(executions.findById(executionId).orElseThrow().version()).isEqualTo(version);
    assertThat(assignments.findActiveByExecutionId(executionId)).isPresent();
    assertThat(cleanings.findByAssignmentId(assignment.resourceAssignmentId())).isEmpty();
    assertThat(outboxTypes()).doesNotContain("WashExecutionCancelled", "CleaningPeriodStarted");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void capacityLossRetryUsesDecisionTimeAfterCleaningExpires(boolean completedHistory) throws Exception {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(900));
    UUID appointment = seedAppointment(UUID.randomUUID(), slot, AppointmentType.IMMUNOCOMPROMISED, "IN_PROGRESS");
    UUID executionId = createInProgress(appointment, AppointmentType.IMMUNOCOMPROMISED, BASE);
    WashExecution pending = executions.findById(executionId).orElseThrow();
    pending.loseResource();
    executions.save(pending, pending.version() - 1);
    int version = pending.version();

    // Keep the pending execution's old assignment: a mistaken cancellation would
    // release it and start a new cleaning because it had physical immuno use.
    ResourceAssignment original = new ResourceAssignment(UUID.randomUUID(), executionId,
        slot, UUID.randomUUID(), UUID.randomUUID(), AssignmentType.INITIAL, null, BASE);
    assignments.save(original);
    UUID cabin = UUID.randomUUID();
    seedCabin(cabin, "C-CLEANING", "IMMUNOCOMPROMISED");
    seedTank(UUID.randomUUID(), cabin, "T-CLEANING", 1);
    Instant sentAt = BASE.plusSeconds(1500);
    Instant cleaningEndsAt = sentAt.plusSeconds(3);
    Instant decisionAt = sentAt.plusSeconds(5);
    Instant cleaningStartsAt = cleaningEndsAt.minusSeconds(3 * 60 * 60L);
    UUID previousExecution = createInProgress(UUID.randomUUID(), AppointmentType.IMMUNOCOMPROMISED,
        cleaningStartsAt.minusSeconds(60));
    UUID historicalAssignment = UUID.randomUUID();
    ResourceAssignment previous = new ResourceAssignment(historicalAssignment, previousExecution,
        slot, cabin, UUID.randomUUID(), AssignmentType.INITIAL, null, cleaningStartsAt.minusSeconds(30));
    assignments.save(previous);
    previous.release(supervisor, cleaningStartsAt, ReleaseReason.NORMAL_COMPLETION);
    assignments.release(previous);
    CleaningPeriod history = new CleaningPeriod(UUID.randomUUID(), previousExecution,
        historicalAssignment, cabin, CleaningTrigger.NORMAL_COMPLETION,
        cleaningStartsAt, 1);
    cleanings.save(history, 0);
    CommandRequest request = new CommandRequest(metadata(supervisor, "capacity-cleaning-retry"),
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "CAPACITY_LOSS", null, supervisor, sentAt, version));
    decisionClock.advanceTo(sentAt);

    // First delivery rolls back technically; the same payload is delivered after
    // the cleaning deadline, without any eligibility writer needed at that time.
    try (var blocker = jdbc.getDataSource().getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.execute("LOCK TABLE cleaning_periods IN ROW EXCLUSIVE MODE");
        assertThatThrownBy(() -> commands.dispatch(request))
            .isInstanceOf(DataAccessException.class)
            .satisfies(error -> assertThat(new TransientFailureClassifier().isTransient(error)).isTrue());
      } finally {
        blocker.rollback();
      }
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM command_idempotency_records", Integer.class)).isZero();
    assertThat(outboxTypes()).isEmpty();
    decisionClock.advanceTo(decisionAt);
    if (completedHistory) {
      history.completeAutomatically(decisionAt);
      cleanings.save(history, 1);
    }
    assertThat(cleanings.findOverlapping(cabin, sentAt, decisionAt.plusSeconds(60))).hasSize(1);
    assertThat(cleanings.findOverlapping(cabin, decisionAt, decisionAt.plusSeconds(60))).isEmpty();

    CommandOutcome result = commands.dispatch(request);
    assertThat(result.status()).isEqualTo(CommandOutcome.Status.REJECTED);
    assertThat(result.errorCode()).isEqualTo("CAPACITY_ALTERNATIVE_AVAILABLE");
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.PENDING_REASSIGNMENT);
    assertThat(executions.findById(executionId).orElseThrow().version()).isEqualTo(version);
    assertThat(assignments.findActiveByExecutionId(executionId).orElseThrow().resourceAssignmentId())
        .isEqualTo(original.resourceAssignmentId());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_assignments", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM cleaning_periods", Integer.class)).isEqualTo(1);
    assertThat(cleanings.findByAssignmentId(original.resourceAssignmentId())).isEmpty();
    assertThat(cleanings.findByAssignmentId(historicalAssignment).orElseThrow().completed())
        .isEqualTo(completedHistory);
    assertThat(outboxTypes()).containsExactly("CancelWashExecutionByClinicRejected");
    assertThat(commands.dispatch(request).errorCode()).isEqualTo("CAPACITY_ALTERNATIVE_AVAILABLE");
    assertThat(outboxTypes()).doesNotContain("WashExecutionCancelled", "CleaningPeriodStarted");
  }

  @Test
  void capacityLossFailsClosedWithoutAppointmentContextButOtherSubreasonsRemainSupported() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID executionId = createPending(UUID.randomUUID());
    int version = executions.findById(executionId).orElseThrow().version();
    CommandOutcome rejected = dispatch(supervisor, "capacity-no-context",
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "CAPACITY_LOSS", null, supervisor, BASE.plusSeconds(1500), version));
    assertThat(rejected.errorCode()).isEqualTo("APPOINTMENT_CONTEXT_UNAVAILABLE");
    assertThat(executions.findById(executionId).orElseThrow().version()).isEqualTo(version);
    CommandOutcome cancelled = dispatch(supervisor, "other-cancellation",
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "OPERATIONAL_REASON", null, supervisor, BASE.plusSeconds(1500), version));
    assertThat(cancelled.status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
  }

  @Test
  void capacityLossFailsClosedWhenAdministrationIsNotReady() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(15 * 60L));
    UUID appointment = seedAppointment(UUID.randomUUID(), slot, AppointmentType.NORMAL, "IN_PROGRESS");
    UUID executionId = createPending(appointment);
    jdbc.update("DELETE FROM projection_recovery_state WHERE projection_name='wash-administration'");
    int version = executions.findById(executionId).orElseThrow().version();
    CommandOutcome rejected = dispatch(supervisor, "capacity-unready",
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "CAPACITY_LOSS", null, supervisor, BASE.plusSeconds(1500), version));
    assertThat(rejected.errorCode()).isEqualTo("RESOURCE_CONFIGURATION_UNAVAILABLE");
    assertThat(executions.findById(executionId).orElseThrow().version()).isEqualTo(version);
    assertThat(outboxTypes()).doesNotContain("WashExecutionCancelled");
  }

  private UUID createPending(UUID appointment) {
    UUID executionId = createInProgress(appointment, AppointmentType.NORMAL, BASE);
    WashExecution execution = executions.findById(executionId).orElseThrow();
    execution.loseResource();
    executions.save(execution, execution.version() - 1);
    return executionId;
  }

  @Test
  void capacityLossPreservesVersionCheckAndReplaysSuccessfulCancellation() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(900));
    UUID appointment = seedAppointment(UUID.randomUUID(), slot, AppointmentType.NORMAL, "IN_PROGRESS");
    UUID executionId = createPending(appointment);
    int version = executions.findById(executionId).orElseThrow().version();
    assertThat(dispatch(supervisor, "stale-capacity",
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "CAPACITY_LOSS", null, supervisor, BASE.plusSeconds(1500), version - 1))
        .errorCode()).isEqualTo("VERSION_CONFLICT");
    CommandRequest request = new CommandRequest(metadata(supervisor, "empty-capacity"),
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "CAPACITY_LOSS", null, supervisor, BASE.plusSeconds(1500), version));
    assertThat(commands.dispatch(request).status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    commands.dispatch(request);
    assertThat(executions.findById(executionId).orElseThrow().version()).isEqualTo(version + 1);
    assertThat(outboxTypes().stream().filter("WashExecutionCancelled"::equals).count()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void capacityLossRollsBackDuringEligibilityChangesAndRechecksOnRetry(int scenario)
      throws Exception {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(900));
    UUID appointment = seedAppointment(UUID.randomUUID(), slot, AppointmentType.NORMAL, "IN_PROGRESS");
    UUID executionId = createPending(appointment);
    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    UUID unavailable = UUID.randomUUID();
    seedCabin(cabin, "C-RACE", "NORMAL");
    if (scenario != 0) {
      seedTank(tank, cabin, "T-RACE", 1);
      if (scenario == 3) {
        UUID other = createInProgress(UUID.randomUUID(), AppointmentType.NORMAL, BASE);
        assignments.save(new ResourceAssignment(
            UUID.randomUUID(), other, slot, cabin, tank, AssignmentType.INITIAL, null, BASE));
      } else if (scenario == 4) {
        assertThat(dispatch(supervisor, "disable-race",
            new WashExecutionCommand.DisableOperationalResource(
                unavailable, null, tank, UnavailabilityCauseType.FAILURE, "Synthetic failure",
                supervisor, null, BASE.plusSeconds(300))).status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
      } else {
        jdbc.update("UPDATE tank_projection SET status='INACTIVE' WHERE tank_id=?", tank);
      }
    }
    int version = executions.findById(executionId).orElseThrow().version();
    CommandRequest request = new CommandRequest(metadata(supervisor, "capacity-race"),
        new WashExecutionCommand.CancelWashExecutionByClinic(
            executionId, "CAPACITY_LOSS", null, supervisor, BASE.plusSeconds(1500), version));
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
        switch (scenario) {
          case 0 -> seedTank(tank, cabin, "T-RACE", 1);
          case 1 -> jdbc.update("UPDATE tank_projection SET status='ACTIVE' WHERE tank_id=?", tank);
          case 2 -> jdbc.queryForObject("SELECT cabin_id FROM cabin_projection WHERE cabin_id=? FOR UPDATE", UUID.class, cabin);
          case 3 -> jdbc.update("UPDATE resource_assignments SET released_at=now(), release_reason='NORMAL_COMPLETION' WHERE tank_id=?", tank);
          case 4 -> assertThat(dispatch(supervisor, "restore-race",
              new WashExecutionCommand.RestoreOperationalResource(
                  unavailable, null, supervisor, BASE.plusSeconds(600), 1)).status())
              .isEqualTo(CommandOutcome.Status.SUCCEEDED);
          default -> throw new IllegalArgumentException("unknown scenario");
        }
        written.countDown();
        try {
          if (!commit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("writer timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        if (scenario == 2) jdbc.update("UPDATE tank_projection SET status='ACTIVE' WHERE tank_id=?", tank);
      }));
      try {
        assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> commands.dispatch(request))
            .isInstanceOf(DataAccessException.class)
            .satisfies(error -> assertThat(new TransientFailureClassifier().isTransient(error)).isTrue());
        assertThat(executions.findById(executionId).orElseThrow().version()).isEqualTo(version);
        assertThat(outboxTypes()).doesNotContain("WashExecutionCancelled", "CancelWashExecutionByClinicRejected");
      } finally {
        commit.countDown();
      }
      writer.get(5, TimeUnit.SECONDS);
    }
    assertThat(commands.dispatch(request).errorCode()).isEqualTo("CAPACITY_ALTERNATIVE_AVAILABLE");
    assertThat(executions.findById(executionId).orElseThrow().status())
        .isEqualTo(WashExecutionStatus.PENDING_REASSIGNMENT);
  }

  @Test
  void sameIdempotencyKeyDoesNotRepeatMutationAndDifferentPayloadIsRejected() {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    UUID slot = seedSlot(UUID.randomUUID(), BASE.plusSeconds(15 * 60L));
    UUID appointment = seedAppointment(student, slot, AppointmentType.NORMAL, "SCHEDULED");
    UUID executionId = UUID.randomUUID();
    CommandMetadata metadata = metadata(supervisor, "stable-arrival-key");
    CommandRequest request = new CommandRequest(
        metadata,
        new WashExecutionCommand.RegisterWashArrival(executionId, appointment, BASE));

    assertThat(commands.dispatch(request).status()).isEqualTo(CommandOutcome.Status.SUCCEEDED);
    assertThat(commands.dispatch(request).status()).isEqualTo(CommandOutcome.Status.DUPLICATE);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM wash_executions WHERE appointment_id=?", Integer.class, appointment))
        .isEqualTo(1);
    assertThat(outboxTypes().stream().filter("WashArrivalRegistered"::equals).count()).isEqualTo(1);

    CommandRequest changed = new CommandRequest(
        metadata,
        new WashExecutionCommand.RegisterWashArrival(
            executionId, appointment, BASE.plusSeconds(1)));
    CommandOutcome collision = commands.dispatch(changed);
    assertThat(collision.status()).isEqualTo(CommandOutcome.Status.REJECTED);
    assertThat(collision.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST");
  }

  @Test
  void requiredProjectionReadinessFailsClosedWithoutDomainMutation() {
    UUID supervisor = UUID.randomUUID();
    authorize(supervisor, "SUPERVISOR_LAVADO");
    jdbc.update("DELETE FROM projection_recovery_state WHERE projection_name='appointments'");
    UUID executionId = UUID.randomUUID();

    CommandOutcome rejected = dispatch(supervisor, "not-ready",
        new WashExecutionCommand.RegisterWashArrival(
            executionId, UUID.randomUUID(), BASE));

    assertThat(rejected.status()).isEqualTo(CommandOutcome.Status.REJECTED);
    assertThat(rejected.errorCode()).isEqualTo("APPOINTMENT_CONTEXT_UNAVAILABLE");
    assertThat(executions.findById(executionId)).isEmpty();
    assertThat(outboxTypes()).contains("RegisterWashArrivalRejected");
  }

  private CommandOutcome dispatch(UUID actor, String key, WashExecutionCommand command) {
    return commands.dispatch(new CommandRequest(metadata(actor, key), command));
  }

  private CommandMetadata metadata(UUID actor, String key) {
    return new CommandMetadata(
        UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
        "platform-bff", key, ActorContext.account(actor));
  }

  private void ready(String projection, String source) {
    jdbc.update("""
        INSERT INTO projection_recovery_state(
          projection_name,source_logical_component_id,readiness_status,last_successful_rebuild_at,last_error,updated_at)
        VALUES(?,?,'READY',now(),NULL,now())
        ON CONFLICT(projection_name) DO UPDATE SET readiness_status='READY',updated_at=now()
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

  private UUID seedSlot(UUID calendarId, Instant startsAt) {
    UUID slotId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO appointment_time_slot_projection(
          appointment_time_slot_id,calendar_id,service_date,starts_at,ends_at,timezone,
          arrival_tolerance_until,status,source_version,updated_at)
        VALUES(?,?,?,?,?,'UTC',?,'ACTIVE',1,now())
        """, slotId, calendarId, SERVICE_DATE, JdbcTemporal.timestamp(startsAt),
        JdbcTemporal.timestamp(startsAt.plusSeconds(60 * 60L)),
        JdbcTemporal.timestamp(startsAt.plusSeconds(10 * 60L)));
    return slotId;
  }

  private UUID seedAppointment(
      UUID studentAccountId, UUID slotId, AppointmentType type, String status) {
    UUID appointmentId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO appointment_projection(
          appointment_id,student_account_id,appointment_time_slot_id,course_section_id,
          appointment_type,instrument_count,piece_type,appointment_status,entry_authorized_at,
          source_version,updated_at)
        VALUES(?,?,?,?,?,1,'INSTRUMENT',?,NULL,1,now())
        """, appointmentId, studentAccountId, slotId, UUID.randomUUID(), type.name(), status);
    return appointmentId;
  }

  private void seedCabin(UUID cabinId, String code, String type) {
    jdbc.update("""
        INSERT INTO cabin_projection(cabin_id,code,name,cabin_type,status,source_version,updated_at)
        VALUES(?,?,?,?,'ACTIVE',1,now())
        """, cabinId, code, code, type);
  }

  private void seedTank(UUID tankId, UUID cabinId, String code, int capacity) {
    jdbc.update("""
        INSERT INTO tank_projection(
          tank_id,cabin_id,code,name,configured_capacity,status,source_version,updated_at)
        VALUES(?,?,?,?,?,'ACTIVE',1,now())
        """, tankId, cabinId, code, code, capacity);
  }

  private UUID createInProgress(UUID appointmentId, AppointmentType type, Instant arrivedAt) {
    UUID executionId = UUID.randomUUID();
    WashExecution execution = WashExecution.register(
        executionId, appointmentId, type, arrivedAt, arrivedAt.plusSeconds(1));
    executions.save(execution, 0);
    execution.start(arrivedAt.plusSeconds(10));
    executions.save(execution, 1);
    return executionId;
  }

  private List<String> outboxTypes() {
    return jdbc.query(
        "SELECT message_type FROM outbox_messages ORDER BY created_at,message_id",
        (rs, row) -> rs.getString(1));
  }
}
