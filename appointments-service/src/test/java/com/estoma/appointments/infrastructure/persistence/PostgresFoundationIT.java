package com.estoma.appointments.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.PendingCapacityEvaluationRepository;
import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class PostgresFoundationIT extends AbstractPostgresIT {
  @Autowired JdbcClient jdbc;
  @Autowired JdbcAppointmentRepository appointments;
  @Autowired JdbcAppointmentTimeSlotRepository slots;
  @Autowired JdbcExceptionalAppointmentAuthorizationRepository authorizations;
  @Autowired JdbcOutboxRepository outbox;
  @Autowired JdbcProcessedMessageRepository processedMessages;
  @Autowired JdbcWashExecutionTransitionRepository transitions;
  @Autowired JdbcAppointmentCapacityProjectionRepository capacity;
  @Autowired JdbcPendingCapacityEvaluationRepository pendingCapacity;
  @Autowired JdbcWashExecutionEvidenceRepository evidence;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired JdbcCommandIdempotencyRepository idempotency;

  @Test void flywayCreatesPr1TablesAndConstraints() {
    assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('appointments','appointment_time_slots','exceptional_appointment_authorizations','processed_messages','outbox_messages','pending_capacity_evaluation')").query(Long.class).single()).isEqualTo(6);
    assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success=true").query(Long.class).single()).isGreaterThanOrEqualTo(1);
  }

  @Test void partialAuthorizationIndexPreservesClinicHistoryAndAllowsReuse() {
    Instant now = Instant.parse("2026-01-01T10:00:00Z"); UUID student = UUID.randomUUID(); UUID authorizationId = UUID.randomUUID();
    authorizations.save(ExceptionalAppointmentAuthorization.grant(authorizationId, student, LocalDate.of(2026,1,1), now, null));
    AppointmentTimeSlot slot = saveSlot(now);
    Appointment first = Appointment.scheduled(UUID.randomUUID(), slot.appointmentTimeSlotId(), student, null, AppointmentType.JOURNEY, 1, PieceType.HIGH_SPEED, authorizationId, now);
    appointments.save(first);
    Appointment clinic = first.cancel(CancellationType.CLINIC, CancellationSubreason.CAPACITY_LOSS, now.plusSeconds(1));
    assertThat(appointments.updateIfVersion(clinic, first.version())).isTrue();
    Appointment second = Appointment.scheduled(UUID.randomUUID(), slot.appointmentTimeSlotId(), student, null, AppointmentType.JOURNEY, 1, PieceType.HIGH_SPEED, authorizationId, now.plusSeconds(2));
    appointments.save(second);
    assertThat(appointments.findByExceptionalAuthorization(authorizationId)).hasSize(2);
  }

  @Test void sameStudentCannotHaveTwoActiveAppointmentsConcurrently() throws Exception {
    Instant now = Instant.parse("2026-01-02T10:00:00Z"); AppointmentTimeSlot slot = saveSlot(now); UUID student = UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2); CountDownLatch go = new CountDownLatch(1);
    Future<Boolean> a = pool.submit(() -> trySave(slot, student, now, ready, go)); Future<Boolean> b = pool.submit(() -> trySave(slot, student, now, ready, go));
    ready.await(); go.countDown(); assertThat(List.of(a.get(), b.get()).stream().filter(Boolean::booleanValue).count()).isEqualTo(1); pool.shutdownNow();
  }

  @Test void exceptionalAuthorizationHasOneConcurrentConsumer() throws Exception {
    Instant now = Instant.parse("2026-01-06T10:00:00Z"); AppointmentTimeSlot slot = saveSlot(now); UUID authorizationId = UUID.randomUUID(); UUID student = UUID.randomUUID(); UUID competingStudent = UUID.randomUUID();
    authorizations.save(ExceptionalAppointmentAuthorization.grant(authorizationId, student, LocalDate.of(2026,1,6), now, null));
    ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2); CountDownLatch go = new CountDownLatch(1);
    Future<Boolean> a = pool.submit(() -> trySaveWithAuthorization(slot, authorizationId, student, now, ready, go)); Future<Boolean> b = pool.submit(() -> trySaveWithAuthorization(slot, authorizationId, competingStudent, now, ready, go));
    ready.await(); go.countDown(); assertThat(List.of(a.get(), b.get()).stream().filter(Boolean::booleanValue).count()).isEqualTo(1); pool.shutdownNow();
  }

  @Test void inboxClaimAndOutboxClaimAreSingleWinnerOperations() throws Exception {
    Instant now = Instant.now(); UUID messageId = UUID.randomUUID(); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2); CountDownLatch go = new CountDownLatch(1);
    Future<Boolean> first = pool.submit(() -> { ready.countDown(); go.await(); return processedMessages.tryClaim(messageId, "Event", now); });
    Future<Boolean> second = pool.submit(() -> { ready.countDown(); go.await(); return processedMessages.tryClaim(messageId, "Event", now); });
    ready.await(); go.countDown(); assertThat(List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    outbox.append(new OutboxRepository.OutboxMessage(UUID.randomUUID(), "Event", 1, "EVENT", null, null, UUID.randomUUID(), null, "{}", now, now, "PENDING", 0, null, null));
    Future<List<OutboxRepository.OutboxMessage>> claimA = pool.submit(() -> outbox.claimBatch(1, "a", now, now.plusSeconds(30))); Future<List<OutboxRepository.OutboxMessage>> claimB = pool.submit(() -> outbox.claimBatch(1, "b", now, now.plusSeconds(30)));
    assertThat(claimA.get().size() + claimB.get().size()).isEqualTo(1); pool.shutdownNow();
  }

  @Test void distinctSameVersionExecutionEventsAreBothStored() {
    UUID execution = UUID.randomUUID(); UUID appointmentId = UUID.randomUUID(); Instant now = Instant.parse("2026-01-03T10:00:00Z"); AppointmentTimeSlot slot = saveSlot(now);
    appointments.save(Appointment.scheduled(appointmentId, slot.appointmentTimeSlotId(), UUID.randomUUID(), null, AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, now));
    assertThat(transitions.record(new WashExecutionTransitionRepository.TransitionRecord(UUID.randomUUID(), execution, appointmentId, "WashEntryAuthorized", 7, UUID.randomUUID(), "AUTHORIZED", now, now))).isTrue();
    assertThat(transitions.record(new WashExecutionTransitionRepository.TransitionRecord(UUID.randomUUID(), execution, appointmentId, "WashExecutionStarted", 7, UUID.randomUUID(), "STARTED", now, now))).isTrue();
  }

  @Test void optimisticVersionAndPendingEvaluationClaimsAreConditional() {
    Instant now = Instant.parse("2026-01-04T10:00:00Z"); AppointmentTimeSlot slot = saveSlot(now);
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(slot.appointmentTimeSlotId(), 1, 1, 1, 0, 0, 0, 1, now));
    assertThat(capacity.updateIfVersion(new AppointmentCapacityProjectionRepository.CapacityProjection(slot.appointmentTimeSlotId(), 2, 1, 1, 0, 0, 0, 2, now.plusSeconds(1)), 1)).isTrue();
    assertThat(capacity.updateIfVersion(new AppointmentCapacityProjectionRepository.CapacityProjection(slot.appointmentTimeSlotId(), 3, 1, 1, 0, 0, 0, 3, now.plusSeconds(2)), 1)).isFalse();
    UUID evaluationId = UUID.randomUUID(); pendingCapacity.enqueue(new PendingCapacityEvaluationRepository.PendingEvaluation(evaluationId, slot.appointmentTimeSlotId(), "RESOURCE_UNAVAILABLE", "PENDING", 0, now, null, null));
    assertThat(pendingCapacity.claim(1, "worker-a", now, now.plusSeconds(30))).hasSize(1);
    assertThat(pendingCapacity.claim(1, "worker-b", now, now.plusSeconds(30))).isEmpty();
  }

  @Test void twoStudentsCompetingForLastProjectedCapacityHaveOneWinner() throws Exception {
    Instant now = Instant.parse("2026-01-08T10:00:00Z"); AppointmentTimeSlot slot = saveSlot(now);
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(slot.appointmentTimeSlotId(), 1, 0, 0, 0, 0, 0, 1, now));
    ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2); CountDownLatch go = new CountDownLatch(1);
    Future<Boolean> a = pool.submit(() -> { ready.countDown(); go.await(); return capacity.tryReserve(slot.appointmentTimeSlotId(), AppointmentType.NORMAL); });
    Future<Boolean> b = pool.submit(() -> { ready.countDown(); go.await(); return capacity.tryReserve(slot.appointmentTimeSlotId(), AppointmentType.NORMAL); });
    ready.await(); go.countDown(); assertThat(List.of(a.get(), b.get()).stream().filter(Boolean::booleanValue).count()).isEqualTo(1); pool.shutdownNow();
  }

  @Test void arrivalAndEntryEvidenceShareAProjectionWithoutVersionCollapse() {
    Instant now = Instant.parse("2026-01-05T10:00:00Z"); AppointmentTimeSlot slot = saveSlot(now); UUID appointmentId = UUID.randomUUID(); UUID executionId = UUID.randomUUID();
    appointments.save(Appointment.scheduled(appointmentId, slot.appointmentTimeSlotId(), UUID.randomUUID(), null, AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, now));
    evidence.recordArrival(new com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository.EvidenceRecord(UUID.randomUUID(), executionId, appointmentId, now.plusSeconds(60), 9L, null, null, UUID.randomUUID(), "WashArrivalRegistered", now));
    evidence.recordEntryAuthorization(new com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository.EvidenceRecord(UUID.randomUUID(), executionId, appointmentId, null, null, now.plusSeconds(120), 9L, UUID.randomUUID(), "WashEntryAuthorized", now.plusSeconds(1)));
    var stored = evidence.findByAppointmentId(appointmentId).orElseThrow();
    assertThat(stored.arrivedAt()).isNotNull(); assertThat(stored.entryAuthorizedAt()).isNotNull();
    assertThat(evidence.hasValidArrival(appointmentId, now.plusSeconds(600))).isTrue();
  }

  @Test void outboxFailureRollsBackTheContainingTransaction() {
    Instant now = Instant.parse("2026-01-07T10:00:00Z");
    UUID slotId = UUID.randomUUID(); AppointmentTimeSlot slot = AppointmentTimeSlot.create(slotId, UUID.randomUUID(), LocalDate.of(2026,1,7), now, now.plusSeconds(1800), ZoneOffset.UTC, now.minusSeconds(600), now.minusSeconds(1200), now.plusSeconds(600));
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      slots.save(slot);
      outbox.append(new OutboxRepository.OutboxMessage(UUID.randomUUID(), "Event", 1, "EVENT", null, null, UUID.randomUUID(), null, "{invalid-json", now, now, "PENDING", 0, null, null));
    })).isInstanceOf(RuntimeException.class);
    assertThat(slots.findById(slotId)).isEmpty();
  }

  @Test void idempotencyUsesScopeKeyAndFingerprintDeterministically() {
    Instant now = Instant.parse("2026-01-09T10:00:00Z"); String scope = "ScheduleAppointment"; String key = UUID.randomUUID().toString(); UUID message = UUID.randomUUID();
    CommandIdempotencyRepository.IdempotencyDecision first = idempotency.claim(scope, key, "fingerprint-a", message, now);
    assertThat(first.accepted()).isTrue(); assertThat(idempotency.complete(scope, key, "{\"ok\":true}", now.plusSeconds(1))).isTrue();
    CommandIdempotencyRepository.IdempotencyDecision replay = idempotency.claim(scope, key, "fingerprint-a", UUID.randomUUID(), now.plusSeconds(2));
    assertThat(replay.replay()).isTrue(); assertThat(replay.conflict()).isFalse();
    assertThat(idempotency.claim(scope, key, "fingerprint-b", UUID.randomUUID(), now.plusSeconds(3)).conflict()).isTrue();
  }

  private boolean trySave(AppointmentTimeSlot slot, UUID student, Instant now, CountDownLatch ready, CountDownLatch go) throws Exception {
    ready.countDown(); go.await();
    try { appointments.save(Appointment.scheduled(UUID.randomUUID(), slot.appointmentTimeSlotId(), student, null, AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, now)); return true; }
    catch (DataIntegrityViolationException ex) { return false; }
  }
  private boolean trySaveWithAuthorization(AppointmentTimeSlot slot, UUID authorizationId, UUID student, Instant now, CountDownLatch ready, CountDownLatch go) throws Exception {
    ready.countDown(); go.await();
    try { appointments.save(Appointment.scheduled(UUID.randomUUID(), slot.appointmentTimeSlotId(), student, null, AppointmentType.JOURNEY, 1, PieceType.HIGH_SPEED, authorizationId, now)); return true; }
    catch (DataIntegrityViolationException ex) { return false; }
  }
  private AppointmentTimeSlot saveSlot(Instant starts) {
    AppointmentTimeSlot slot = AppointmentTimeSlot.create(UUID.randomUUID(), UUID.randomUUID(), LocalDate.ofInstant(starts, ZoneOffset.UTC), starts, starts.plusSeconds(1800), ZoneOffset.UTC, starts.minusSeconds(600), starts.minusSeconds(1200), starts.plusSeconds(600)); slots.save(slot); return slot;
  }
}
