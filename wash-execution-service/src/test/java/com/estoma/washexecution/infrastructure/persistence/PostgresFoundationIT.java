package com.estoma.washexecution.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washexecution.application.port.AffectedExecutionRepository;
import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.OutboxRepository;
import com.estoma.washexecution.application.port.ProcessedMessageRepository;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.ResourceConcurrencyRepository;
import com.estoma.washexecution.application.port.ResourceUnavailabilityRepository;
import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.AssignmentType;
import com.estoma.washexecution.domain.execution.CleaningPeriod;
import com.estoma.washexecution.domain.execution.CleaningTrigger;
import com.estoma.washexecution.domain.execution.ReleaseReason;
import com.estoma.washexecution.domain.execution.ResourceAssignment;
import com.estoma.washexecution.domain.execution.ResourceUnavailability;
import com.estoma.washexecution.domain.execution.UnavailabilityCauseType;
import com.estoma.washexecution.domain.execution.WashExecution;
import com.estoma.washexecution.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PostgresFoundationIT extends AbstractPostgresIT {
  @Autowired WashExecutionRepository executions;
  @Autowired ResourceAssignmentRepository assignments;
  @Autowired ResourceConcurrencyRepository resourceLocks;
  @Autowired ResourceUnavailabilityRepository unavailabilities;
  @Autowired AffectedExecutionRepository affectedExecutions;
  @Autowired CleaningPeriodRepository cleaningPeriods;
  @Autowired ProcessedMessageRepository inbox;
  @Autowired OutboxRepository outbox;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void migrationUsesCanonicalAuthorizationAndDurableInboxSchema() {
    assertThat(regclass("authorization_role_projection")).isEqualTo("authorization_role_projection");
    assertThat(regclass("authorization_access_projection")).isEqualTo("authorization_access_projection");
    assertThat(regclass("role_projection")).isNull();
    assertThat(regclass("system_access_projection")).isNull();

    assertThat(columnExists("processed_messages", "status")).isTrue();
    assertThat(columnExists("processed_messages", "deferred_reason")).isTrue();
    assertThat(columnExists("processed_messages", "applied_at")).isTrue();
    assertThat(columnExists("processed_messages", "claim_token")).isTrue();
    assertThat(columnExists("processed_messages", "lease_until")).isTrue();
  }

  @Test
  void executionRepositoryRoundTripsAndAppointmentIsUnique() {
    Instant now = Instant.parse("2026-08-23T15:00:00Z");
    UUID appointmentId = UUID.randomUUID();
    WashExecution first = WashExecution.register(UUID.randomUUID(), appointmentId, AppointmentType.NORMAL, now, now);
    executions.save(first, 0);

    assertThat(executions.findByAppointmentId(appointmentId).orElseThrow().status()).isEqualTo(first.status());

    WashExecution duplicate = WashExecution.register(UUID.randomUUID(), appointmentId, AppointmentType.NORMAL, now, now);
    assertThatThrownBy(() -> executions.save(duplicate, 0)).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void optimisticVersionPreventsTwoTerminalMutationsFromSameSnapshot() {
    UUID id = seedExecution();
    WashExecution first = executions.findById(id).orElseThrow();
    WashExecution stale = executions.findById(id).orElseThrow();

    first.rejectEntry();
    executions.save(first, 1);

    stale.authorizeWithoutResource();
    assertThatThrownBy(() -> executions.save(stale, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("VERSION_CONFLICT");
  }

  @Test
  void oneActiveAssignmentPerExecutionIsProtectedByConstraint() {
    UUID executionId = seedExecution();
    UUID slotId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    Instant now = Instant.now();

    assignments.save(new ResourceAssignment(UUID.randomUUID(), executionId, slotId, cabinId, tankId,
        AssignmentType.INITIAL, null, now));

    assertThatThrownBy(() -> assignments.save(new ResourceAssignment(UUID.randomUUID(), executionId, slotId,
        cabinId, tankId, AssignmentType.INITIAL, null, now.plusSeconds(1))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void lastTankSlotCapacityIsSerializedByCabinThenTankLock() throws Exception {
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    seedResource(cabinId, tankId, 1);
    UUID executionA = seedExecution();
    UUID executionB = seedExecution();

    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<Boolean> a = pool.submit(() -> attemptAssignment(start, executionA, cabinId, tankId, slotId));
      Future<Boolean> b = pool.submit(() -> attemptAssignment(start, executionB, cabinId, tankId, slotId));
      start.countDown();

      assertThat(a.get()).isNotEqualTo(b.get());
      assertThat(assignments.countActiveByTankAndSlot(tankId, slotId)).isEqualTo(1);
    }
  }

  @Test
  void assignmentRevalidatesAfterConcurrentDisable() throws Exception {
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID executionId = seedExecution();
    seedResource(cabinId, tankId, 1);

    CountDownLatch disableLocked = new CountDownLatch(1);
    CountDownLatch allowDisableCommit = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<Void> disable = pool.submit(() -> {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
          resourceLocks.lockCabinAndTank(cabinId, tankId);
          disableLocked.countDown();
          await(allowDisableCommit);
          unavailabilities.save(new ResourceUnavailability(
              UUID.randomUUID(), null, null, tankId, UnavailabilityCauseType.FAILURE,
              "failure", UUID.randomUUID(), Instant.now(), 1), 0);
        });
        return null;
      });

      disableLocked.await();
      Future<Boolean> assign = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
        resourceLocks.lockCabinAndTank(cabinId, tankId);
        if (!unavailabilities.findActiveForResource(null, tankId).isEmpty()) return false;
        assignments.save(new ResourceAssignment(UUID.randomUUID(), executionId, slotId, cabinId, tankId,
            AssignmentType.INITIAL, null, Instant.now()));
        return true;
      }));

      allowDisableCommit.countDown();
      disable.get();
      assertThat(assign.get()).isFalse();
      assertThat(assignments.countActiveByTankAndSlot(tankId, slotId)).isZero();
    }
  }

  @Test
  void oneUnavailabilityCanPersistMultipleAffectedExecutions() {
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    UUID executionA = seedExecution();
    UUID executionB = seedExecution();
    ResourceAssignment a = new ResourceAssignment(UUID.randomUUID(), executionA, UUID.randomUUID(), cabinId, tankId,
        AssignmentType.INITIAL, null, Instant.now());
    ResourceAssignment b = new ResourceAssignment(UUID.randomUUID(), executionB, UUID.randomUUID(), cabinId, tankId,
        AssignmentType.INITIAL, null, Instant.now());
    assignments.save(a);
    assignments.save(b);

    ResourceUnavailability failure = new ResourceUnavailability(
        UUID.randomUUID(), executionA, null, tankId, UnavailabilityCauseType.FAILURE,
        "failure", UUID.randomUUID(), Instant.now(), 1);
    unavailabilities.save(failure, 0);
    affectedExecutions.add(failure.resourceUnavailabilityId(), executionA, a.resourceAssignmentId(), Instant.now());
    affectedExecutions.add(failure.resourceUnavailabilityId(), executionB, b.resourceAssignmentId(), Instant.now());

    assertThat(affectedExecutions.findByUnavailabilityId(failure.resourceUnavailabilityId())).hasSize(2);
  }

  @Test
  void expiredCleaningPeriodIsCompletedByExactlyOneConcurrentWorker() throws Exception {
    UUID executionId = seedExecution();
    UUID assignmentId = UUID.randomUUID();
    Instant assignedAt = Instant.now().minusSeconds(5 * 60 * 60);
    Instant releasedAt = Instant.now().minusSeconds(4 * 60 * 60);
    ResourceAssignment assignment = new ResourceAssignment(
        assignmentId, executionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        AssignmentType.INITIAL, null, assignedAt);
    assignments.save(assignment);
    assignment.release(null, releasedAt, ReleaseReason.NORMAL_COMPLETION);
    assignments.release(assignment);

    CleaningPeriod cleaning = new CleaningPeriod(
        UUID.randomUUID(), executionId, assignmentId, assignment.cabinId(), CleaningTrigger.NORMAL_COMPLETION,
        releasedAt, 1);
    cleaningPeriods.save(cleaning, 0);

    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<Boolean> a = pool.submit(() -> completeOneExpiredCleaning(start));
      Future<Boolean> b = pool.submit(() -> completeOneExpiredCleaning(start));
      start.countDown();
      assertThat(a.get()).isNotEqualTo(b.get());
      assertThat(cleaningPeriods.findByAssignmentId(assignmentId).orElseThrow().completedAt())
          .isEqualTo(postgresTimestamp(cleaning.expectedEndsAt()));
    }
  }

  @Test
  void transactionalOutboxAppendRollsBackWithCallerTransaction() {
    UUID committedId = UUID.randomUUID();
    Instant now = Instant.now();
    OutboxRepository.OutboxMessage committed = outboxMessage(committedId, now);
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> outbox.append(committed));
    assertThat(outboxCount(committedId)).isEqualTo(1);

    UUID rolledBackId = UUID.randomUUID();
    assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      outbox.append(outboxMessage(rolledBackId, now));
      throw new IllegalStateException("force rollback");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(outboxCount(rolledBackId)).isZero();
  }

  @Test
  void deferredInboxClaimIsLeasedAndFenced() {
    Instant now = Instant.now();
    UUID messageId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    var message = new ProcessedMessageRepository.ProcessedMessage(
        "appointments-events", messageId, ProcessedMessageRepository.Status.DEFERRED,
        "AppointmentScheduled", 1, "EVENT", "appointments", correlationId, null, null, null,
        UUID.randomUUID(), 4L, null, "{}", now, now, null, "EVENT_PENDING", now);

    assertThat(inbox.insertIfAbsent(message)).isTrue();
    assertThat(inbox.insertIfAbsent(message)).isFalse();

    UUID firstClaim = UUID.randomUUID();
    UUID competingClaim = UUID.randomUUID();
    assertThat(inbox.claimDeferred("appointments-events", 10, now, firstClaim, now.plusSeconds(30)))
        .extracting(ProcessedMessageRepository.ProcessedMessage::messageId).containsExactly(messageId);
    assertThat(inbox.claimDeferred("appointments-events", 10, now.plusSeconds(1), competingClaim, now.plusSeconds(31)))
        .isEmpty();
    assertThat(inbox.markApplied("appointments-events", messageId, competingClaim, now.plusSeconds(2))).isFalse();
    assertThat(inbox.markApplied("appointments-events", messageId, firstClaim, now.plusSeconds(2))).isTrue();
    assertThat(inbox.find("appointments-events", messageId).orElseThrow().status())
        .isEqualTo(ProcessedMessageRepository.Status.APPLIED);
  }

  @Test
  void duplicateActiveUnavailabilityForSameResourceIsRejected() {
    UUID cabinId = UUID.randomUUID();
    String insert = """
        INSERT INTO resource_unavailabilities(
          resource_unavailability_id,cabin_id,cause_type,reason,disabled_by_account_id,disabled_at,
          version,created_at,updated_at)
        VALUES(?,?,'FAILURE','failure',?,now(),1,now(),now())
        """;
    jdbc.update(insert, UUID.randomUUID(), cabinId, UUID.randomUUID());
    assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), cabinId, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private boolean completeOneExpiredCleaning(CountDownLatch start) throws Exception {
    start.await();
    Boolean result = new TransactionTemplate(transactionManager).execute(status -> {
      var expired = cleaningPeriods.findExpiredForUpdate(Instant.now());
      if (expired.isEmpty()) return false;
      CleaningPeriod period = expired.getFirst();
      period.completeAutomatically(Instant.now());
      cleaningPeriods.save(period, 1);
      return true;
    });
    return Boolean.TRUE.equals(result);
  }

  private static Instant postgresTimestamp(Instant value) {
    return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
  }

  private boolean attemptAssignment(
      CountDownLatch start, UUID executionId, UUID cabinId, UUID tankId, UUID slotId) throws Exception {
    start.await();
    Boolean result = new TransactionTemplate(transactionManager).execute(status -> {
      resourceLocks.lockCabinAndTank(cabinId, tankId);
      int current = assignments.countActiveByTankAndSlot(tankId, slotId);
      if (current >= 1) return false;
      assignments.save(new ResourceAssignment(UUID.randomUUID(), executionId, slotId, cabinId, tankId,
          AssignmentType.INITIAL, null, Instant.now()));
      return true;
    });
    return Boolean.TRUE.equals(result);
  }

  private OutboxRepository.OutboxMessage outboxMessage(UUID messageId, Instant now) {
    return new OutboxRepository.OutboxMessage(
        messageId, "WashArrivalRegistered", 1, "EVENT", "wash-execution", "wash-execution",
        now, UUID.randomUUID(), null, UUID.randomUUID(), 1, UUID.randomUUID(), null, null, "{}");
  }

  private int outboxCount(UUID messageId) {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE message_id=?", Integer.class, messageId);
    return count == null ? 0 : count;
  }

  private UUID seedExecution() {
    UUID executionId = UUID.randomUUID();
    Instant now = Instant.now();
    executions.save(WashExecution.register(executionId, UUID.randomUUID(), AppointmentType.NORMAL, now, now), 0);
    return executionId;
  }

  private void seedResource(UUID cabinId, UUID tankId, int capacity) {
    jdbc.update("""
        INSERT INTO cabin_projection(cabin_id,code,name,cabin_type,status,source_version,updated_at)
        VALUES(?,?,'Cabin','NORMAL','ACTIVE',1,now())
        """, cabinId, "C-" + cabinId.toString().substring(0, 8));
    jdbc.update("""
        INSERT INTO tank_projection(tank_id,cabin_id,code,name,configured_capacity,status,source_version,updated_at)
        VALUES(?,?,?,'Tank',?,'ACTIVE',1,now())
        """, tankId, cabinId, "T-" + tankId.toString().substring(0, 8), capacity);
  }

  private String regclass(String name) {
    return jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, "public." + name);
  }

  private boolean columnExists(String table, String column) {
    Integer count = jdbc.queryForObject("""
        SELECT count(*) FROM information_schema.columns
        WHERE table_schema='public' AND table_name=? AND column_name=?
        """, Integer.class, table, column);
    return count != null && count == 1;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
