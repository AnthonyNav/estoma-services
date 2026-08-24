package com.estoma.appointments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplicationBehaviorTest {
  private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void sameDeferredCompletionConvergesAfterEarlierStartArrives() {
    Fixture fixture = new Fixture();
    UUID execution = UUID.randomUUID();
    UUID appointmentId = fixture.createAppointment();
    UUID completionSource = UUID.randomUUID();
    ExecutionEvent completion = event("WashExecutionCompleted", execution, appointmentId,
        completionSource, 5, NOW.plusSeconds(90), UUID.randomUUID());

    assertThat(fixture.completedHandler().handle(completion).status())
        .isEqualTo(ApplicationResult.Status.DEFERRED);
    assertThat(fixture.pending.hasOpenForAppointment(appointmentId)).isTrue();

    assertThat(fixture.startedHandler().handle(event("WashExecutionStarted", execution, appointmentId,
        UUID.randomUUID(), 4, NOW.plusSeconds(30), null)).status())
        .isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.IN_PROGRESS);

    assertThat(fixture.completedHandler().handle(completion).status())
        .isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.COMPLETED);
    assertThat(fixture.pending.hasOpenForAppointment(appointmentId)).isFalse();
    assertThat(fixture.inbox.states.get(completionSource)).isEqualTo("APPLIED");
  }

  @Test
  void entryAuthorizationAndStartWithSameExecutionVersionAreBothApplied() {
    Fixture fixture = new Fixture();
    UUID execution = UUID.randomUUID();
    UUID appointmentId = fixture.createAppointment();
    HandleWashEntryAuthorizedHandler authorized = fixture.authorizedHandler();

    assertThat(authorized.handle(event("WashEntryAuthorized", execution, appointmentId,
        UUID.randomUUID(), 7, NOW.plusSeconds(30), UUID.randomUUID())).status())
        .isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.startedHandler().handle(event("WashExecutionStarted", execution, appointmentId,
        UUID.randomUUID(), 7, NOW.plusSeconds(30), null)).status())
        .isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.appointments.get(appointmentId).entryAuthorizedAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.IN_PROGRESS);
  }

  @Test
  void lateEntryAuthorizationCanCompleteMetadataAfterCompletion() {
    Fixture fixture = new Fixture();
    UUID execution = UUID.randomUUID();
    UUID appointmentId = fixture.createAppointment();
    fixture.startedHandler().handle(event("WashExecutionStarted", execution, appointmentId,
        UUID.randomUUID(), 4, NOW.plusSeconds(30), null));
    fixture.completedHandler().handle(event("WashExecutionCompleted", execution, appointmentId,
        UUID.randomUUID(), 5, NOW.plusSeconds(90), UUID.randomUUID()));

    assertThat(fixture.authorizedHandler().handle(event("WashEntryAuthorized", execution, appointmentId,
        UUID.randomUUID(), 4, NOW.plusSeconds(20), UUID.randomUUID())).status())
        .isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.COMPLETED);
    assertThat(fixture.appointments.get(appointmentId).entryAuthorizedAt()).isEqualTo(NOW.plusSeconds(20));
  }

  @Test
  void olderExecutionTransitionCannotOverwriteNewerState() {
    Fixture fixture = new Fixture();
    UUID execution = UUID.randomUUID();
    UUID appointmentId = fixture.createAppointment();
    assertThat(fixture.startedHandler().handle(event("WashExecutionStarted", execution, appointmentId,
        UUID.randomUUID(), 4, NOW.plusSeconds(30), null)).status())
        .isEqualTo(ApplicationResult.Status.APPLIED);
    ApplicationResult stale = fixture.rejectedHandler().handle(event("WashEntryRejected", execution,
        appointmentId, UUID.randomUUID(), 3, NOW.plusSeconds(20), UUID.randomUUID()));
    assertThat(stale.status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(fixture.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.IN_PROGRESS);
  }

  private static ExecutionEvent event(String type, UUID execution, UUID appointment, UUID source,
      long version, Instant actionAt, UUID actor) {
    return new ExecutionEvent(UUID.randomUUID(), source, execution, appointment, type, version, NOW,
        null, actionAt, actor, type.equals("WashExecutionCancelled") ? NOW.plusSeconds(10) : null,
        type.equals("WashExecutionCancelled") ? "CLINIC" : null,
        type.equals("WashExecutionCancelled") ? "CAPACITY_LOSS" : null,
        "execution event", "{}");
  }

  private static final class Fixture {
    final MemoryAppointments appointments = new MemoryAppointments();
    final MemoryInbox inbox = new MemoryInbox();
    final MemoryPending pending = new MemoryPending();
    final MemoryTransitions transitions = new MemoryTransitions();
    final MemoryEvidence evidence = new MemoryEvidence();
    final MemoryOutbox outbox = new MemoryOutbox();
    final PendingCapacityResolutionCoordinator capacityResolution = mock(PendingCapacityResolutionCoordinator.class);

    UUID createAppointment() {
      UUID id = UUID.randomUUID();
      appointments.save(Appointment.scheduled(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
          AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW));
      return id;
    }
    HandleWashExecutionCompletedHandler completedHandler() {
      return new HandleWashExecutionCompletedHandler(appointments, inbox, transitions, pending, outbox,
          capacityResolution);
    }
    HandleWashExecutionStartedHandler startedHandler() {
      return new HandleWashExecutionStartedHandler(appointments, inbox, transitions, pending, outbox);
    }
    HandleWashEntryRejectedHandler rejectedHandler() {
      return new HandleWashEntryRejectedHandler(appointments, inbox, transitions, pending, outbox);
    }
    HandleWashEntryAuthorizedHandler authorizedHandler() {
      return new HandleWashEntryAuthorizedHandler(appointments, evidence, inbox, pending);
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
      values.put(value.appointmentId(), value); return true;
    }
    @Override public long countActiveForStudent(UUID student, LocalDate date) { return 0; }
    @Override public long countCompletedForStudent(UUID student, LocalDate date) { return 0; }
    @Override public List<Appointment> findForSlot(UUID slot, boolean lock) { return List.of(); }
    @Override public List<Appointment> findForStudentAndDate(UUID student, LocalDate date) { return List.of(); }
    @Override public Optional<Appointment> findActiveByExceptionalAuthorizationForUpdate(UUID id) { return Optional.empty(); }
    @Override public List<Appointment> findByExceptionalAuthorization(UUID id) { return List.of(); }
    @Override public List<Appointment> findByStatus(AppointmentStatus status) { return List.of(); }
    @Override public List<Appointment> findMissedCandidates(Instant cutoff, int limit) { return List.of(); }
    @Override public List<Appointment> findForServiceDatePage(LocalDate date, UUID after, int limit) { return List.of(); }
  }

  private static final class MemoryInbox implements ProcessedMessageRepository {
    final Map<UUID, String> states = new HashMap<>();
    @Override public boolean tryClaim(UUID id, String type, Instant at) {
      String current = states.get(id);
      if (current == null || "DEFERRED".equals(current)) { states.put(id, "CLAIMED"); return true; }
      return false;
    }
    @Override public boolean markApplied(UUID id, Instant at) { states.put(id, "APPLIED"); return true; }
    @Override public boolean markDeferred(UUID id, String reason, Instant at) { states.put(id, "DEFERRED"); return true; }
    @Override public boolean wasProcessed(UUID id) { return "APPLIED".equals(states.get(id)); }
  }

  private static final class MemoryPending implements PendingWashExecutionEventRepository {
    final List<PendingEvent> events = new ArrayList<>();
    @Override public void defer(PendingEvent event) {
      if (events.stream().noneMatch(e -> e.sourceEventId().equals(event.sourceEventId()))) events.add(event);
    }
    @Override public List<PendingEvent> claim(int limit, String worker, Instant now, Instant leaseUntil) { return List.of(); }
    @Override public boolean complete(UUID id, String worker, Instant at) { return true; }
    @Override public boolean discardBySourceEventId(UUID sourceEventId, Instant at) {
      return events.removeIf(e -> e.sourceEventId().equals(sourceEventId));
    }
    @Override public boolean hasOpenForAppointment(UUID appointmentId) {
      return events.stream().anyMatch(e -> e.appointmentId().equals(appointmentId));
    }
  }

  private static final class MemoryTransitions implements WashExecutionTransitionRepository {
    final List<TransitionRecord> values = new ArrayList<>();
    @Override public boolean record(TransitionRecord transition) { values.add(transition); return true; }
    @Override public Optional<TransitionRecord> find(UUID appointment, String type) {
      return values.stream().filter(v -> v.appointmentId().equals(appointment) && v.eventType().equals(type))
          .max(Comparator.comparingLong(TransitionRecord::executionVersion));
    }
    @Override public Optional<TransitionRecord> findLatestForExecution(UUID execution) {
      return values.stream().filter(v -> v.washExecutionId().equals(execution))
          .max(Comparator.comparingLong(TransitionRecord::executionVersion));
    }
  }

  private static final class MemoryEvidence implements WashExecutionEvidenceRepository {
    final Map<UUID, EvidenceRecord> values = new HashMap<>();
    @Override public EvidenceRecord recordArrival(EvidenceRecord value) { values.put(value.appointmentId(), value); return value; }
    @Override public EvidenceRecord recordEntryAuthorization(EvidenceRecord value) {
      EvidenceRecord existing = values.get(value.appointmentId());
      values.put(value.appointmentId(), existing == null ? value : new EvidenceRecord(existing.evidenceId(),
          value.washExecutionId(), value.appointmentId(), existing.arrivedAt(), existing.arrivalExecutionVersion(),
          value.entryAuthorizedAt(), value.entryAuthorizedExecutionVersion(), value.sourceEventId(),
          value.sourceEventType(), value.updatedAt()));
      return values.get(value.appointmentId());
    }
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
