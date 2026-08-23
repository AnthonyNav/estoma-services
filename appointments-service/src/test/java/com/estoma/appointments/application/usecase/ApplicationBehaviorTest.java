package com.estoma.appointments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentStatus;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplicationBehaviorTest {
  private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void completionBeforeStartIsDeferredAndConvergesAfterStart() {
    Fixture fixture = new Fixture();
    HandleWashExecutionCompletedHandler completed = fixture.completedHandler();
    HandleWashExecutionStartedHandler started = fixture.startedHandler();
    UUID execution = UUID.randomUUID();
    UUID appointmentId = fixture.createAppointment();

    ApplicationResult deferred = completed.handle(event("WashExecutionCompleted", execution, appointmentId,
        UUID.randomUUID(), 4, null));
    assertThat(deferred.status()).isEqualTo(ApplicationResult.Status.DEFERRED);
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.SCHEDULED);
    assertThat(fixture.pending.events).hasSize(1);

    ApplicationResult startedResult = started.handle(event("WashExecutionStarted", execution, appointmentId,
        UUID.randomUUID(), 4, null));
    assertThat(startedResult.status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.IN_PROGRESS);

    ApplicationResult completedResult = completed.handle(event("WashExecutionCompleted", execution, appointmentId,
        UUID.randomUUID(), 4, null));
    assertThat(completedResult.status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.COMPLETED);
  }

  @Test
  void entryAuthorizationAndStartWithSameExecutionVersionAreBothApplied() {
    Fixture fixture = new Fixture();
    UUID execution = UUID.randomUUID();
    UUID appointmentId = fixture.createAppointment();
    HandleWashEntryAuthorizedHandler authorized = new HandleWashEntryAuthorizedHandler(
        fixture.appointments, fixture.evidence, fixture.inbox);
    HandleWashExecutionStartedHandler started = fixture.startedHandler();

    assertThat(authorized.handle(event("WashEntryAuthorized", execution, appointmentId,
        UUID.randomUUID(), 7, NOW.plusSeconds(30))).status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(started.handle(event("WashExecutionStarted", execution, appointmentId,
        UUID.randomUUID(), 7, null)).status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.appointments.get(appointmentId).entryAuthorizedAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.IN_PROGRESS);
  }

  private static ExecutionEvent event(String type, UUID execution, UUID appointment, UUID source,
      long version, Instant decidedAt) {
    return new ExecutionEvent(UUID.randomUUID(), source, execution, appointment, type, version, NOW,
        null, decidedAt, "execution event", "{}");
  }

  private static final class Fixture {
    final MemoryAppointments appointments = new MemoryAppointments();
    final MemoryInbox inbox = new MemoryInbox();
    final MemoryPending pending = new MemoryPending();
    final MemoryTransitions transitions = new MemoryTransitions();
    final MemoryEvidence evidence = new MemoryEvidence();
    final MemoryOutbox outbox = new MemoryOutbox();

    UUID createAppointment() {
      UUID id = UUID.randomUUID();
      appointments.save(Appointment.scheduled(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
          AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW));
      return id;
    }

    HandleWashExecutionCompletedHandler completedHandler() {
      return new HandleWashExecutionCompletedHandler(appointments, inbox, transitions, pending, outbox);
    }

    HandleWashExecutionStartedHandler startedHandler() {
      return new HandleWashExecutionStartedHandler(appointments, inbox, transitions, pending, outbox);
    }
  }

  private static final class MemoryAppointments implements AppointmentRepository {
    final Map<UUID, Appointment> values = new HashMap<>();
    Appointment get(UUID id) { return values.get(id); }
    @Override public Appointment save(Appointment value) { values.put(value.appointmentId(), value); return value; }
    @Override public Optional<Appointment> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
    @Override public Optional<Appointment> findByIdForUpdate(UUID id) { return findById(id); }
    @Override public boolean updateIfVersion(Appointment value, long expectedVersion) {
      Appointment current = values.get(value.appointmentId());
      if (current == null || current.version() != expectedVersion) return false;
      values.put(value.appointmentId(), value);
      return true;
    }
    @Override public long countActiveForStudent(UUID student, java.time.LocalDate date) { return 0; }
    @Override public long countCompletedForStudent(UUID student, java.time.LocalDate date) { return 0; }
    @Override public List<Appointment> findForSlot(UUID slot, boolean lock) { return List.of(); }
    @Override public List<Appointment> findForStudentAndDate(UUID student, java.time.LocalDate date) { return List.of(); }
    @Override public Optional<Appointment> findActiveByExceptionalAuthorizationForUpdate(UUID id) { return Optional.empty(); }
    @Override public List<Appointment> findByExceptionalAuthorization(UUID id) { return List.of(); }
    @Override public List<Appointment> findByStatus(AppointmentStatus status) { return List.of(); }
  }

  private static final class MemoryInbox implements ProcessedMessageRepository {
    final Map<UUID, String> states = new HashMap<>();
    @Override public boolean tryClaim(UUID id, String type, Instant at) { return states.putIfAbsent(id, "CLAIMED") == null; }
    @Override public boolean markApplied(UUID id, Instant at) { states.put(id, "APPLIED"); return true; }
    @Override public boolean markDeferred(UUID id, String reason, Instant at) { states.put(id, "DEFERRED"); return true; }
    @Override public boolean wasProcessed(UUID id) { return "APPLIED".equals(states.get(id)); }
  }

  private static final class MemoryPending implements PendingWashExecutionEventRepository {
    final List<PendingEvent> events = new ArrayList<>();
    @Override public void defer(PendingEvent event) { events.add(event); }
    @Override public List<PendingEvent> claim(int limit, String worker, Instant now, Instant leaseUntil) { return List.of(); }
    @Override public boolean complete(UUID id, String worker, Instant at) { return true; }
  }

  private static final class MemoryTransitions implements WashExecutionTransitionRepository {
    final List<TransitionRecord> values = new ArrayList<>();
    @Override public boolean record(TransitionRecord transition) { values.add(transition); return true; }
    @Override public Optional<TransitionRecord> find(UUID appointment, String type) { return Optional.empty(); }
  }

  private static final class MemoryEvidence implements WashExecutionEvidenceRepository {
    final Map<UUID, EvidenceRecord> values = new HashMap<>();
    @Override public EvidenceRecord recordArrival(EvidenceRecord value) { values.put(value.appointmentId(), value); return value; }
    @Override public EvidenceRecord recordEntryAuthorization(EvidenceRecord value) { values.put(value.appointmentId(), value); return value; }
    @Override public Optional<EvidenceRecord> findByAppointmentId(UUID id) { return Optional.ofNullable(values.get(id)); }
    @Override public Optional<EvidenceRecord> findByExecutionId(UUID id) { return values.values().stream().filter(v -> v.washExecutionId().equals(id)).findFirst(); }
    @Override public boolean hasValidArrival(UUID id, Instant tolerance) { return false; }
  }

  private static final class MemoryOutbox implements OutboxRepository {
    final List<OutboxMessage> values = new ArrayList<>();
    @Override public void append(OutboxMessage message) { values.add(message); }
    @Override public List<OutboxMessage> claimBatch(int limit, String worker, Instant now, Instant leaseUntil) { return List.of(); }
    @Override public boolean markPublished(UUID id, String worker, Instant at) { return true; }
    @Override public boolean markFailed(UUID id, String worker, String error, Instant at) { return true; }
  }
}
