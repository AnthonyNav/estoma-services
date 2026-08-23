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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplicationBehaviorTest {
  private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

  @Test void sameDeferredCompletionConvergesAfterEarlierStartArrives() {
    Fixture f = new Fixture();
    UUID execution = UUID.randomUUID(); UUID appointmentId = f.createAppointment(); UUID source = UUID.randomUUID();
    ExecutionEvent completion = event("WashExecutionCompleted", execution, appointmentId, source, 5, NOW.plusSeconds(90), UUID.randomUUID());
    assertThat(f.completedHandler().handle(completion).status()).isEqualTo(ApplicationResult.Status.DEFERRED);
    assertThat(f.pending.hasOpenForAppointment(appointmentId)).isTrue();
    assertThat(f.startedHandler().handle(event("WashExecutionStarted", execution, appointmentId, UUID.randomUUID(), 4, NOW.plusSeconds(30), null)).status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(f.completedHandler().handle(completion).status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(f.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.COMPLETED);
    assertThat(f.pending.hasOpenForAppointment(appointmentId)).isFalse();
  }

  @Test void entryAuthorizationAndStartWithSameExecutionVersionAreBothApplied() {
    Fixture f = new Fixture(); UUID execution = UUID.randomUUID(); UUID appointmentId = f.createAppointment();
    HandleWashEntryAuthorizedHandler authorized = new HandleWashEntryAuthorizedHandler(f.appointments, f.evidence, f.inbox, f.pending);
    assertThat(authorized.handle(event("WashEntryAuthorized", execution, appointmentId, UUID.randomUUID(), 7, NOW.plusSeconds(30), UUID.randomUUID())).status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(f.startedHandler().handle(event("WashExecutionStarted", execution, appointmentId, UUID.randomUUID(), 7, NOW.plusSeconds(30), null)).status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(f.appointments.get(appointmentId).entryAuthorizedAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(f.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.IN_PROGRESS);
  }

  @Test void olderExecutionTransitionCannotOverwriteNewerState() {
    Fixture f = new Fixture(); UUID execution = UUID.randomUUID(); UUID appointmentId = f.createAppointment();
    assertThat(f.startedHandler().handle(event("WashExecutionStarted", execution, appointmentId, UUID.randomUUID(), 4, NOW.plusSeconds(30), null)).status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(f.rejectedHandler().handle(event("WashEntryRejected", execution, appointmentId, UUID.randomUUID(), 3, NOW.plusSeconds(20), UUID.randomUUID())).status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(f.appointments.get(appointmentId).status()).isEqualTo(AppointmentStatus.IN_PROGRESS);
  }

  private static ExecutionEvent event(String type, UUID execution, UUID appointment, UUID source, long version, Instant actionAt, UUID actor) {
    return new ExecutionEvent(UUID.randomUUID(), source, execution, appointment, type, version, NOW, null, actionAt, actor,
        type.equals("WashExecutionCancelled") ? NOW.plusSeconds(10) : null,
        type.equals("WashExecutionCancelled") ? "CLINIC" : null,
        type.equals("WashExecutionCancelled") ? "CAPACITY_LOSS" : null, "execution event", "{}");
  }

  private static final class Fixture {
    final MemoryAppointments appointments = new MemoryAppointments(); final MemoryInbox inbox = new MemoryInbox();
    final MemoryPending pending = new MemoryPending(); final MemoryTransitions transitions = new MemoryTransitions();
    final MemoryEvidence evidence = new MemoryEvidence(); final MemoryOutbox outbox = new MemoryOutbox();
    UUID createAppointment() { UUID id=UUID.randomUUID(); appointments.save(Appointment.scheduled(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),AppointmentType.NORMAL,1,PieceType.HIGH_SPEED,null,NOW)); return id; }
    HandleWashExecutionCompletedHandler completedHandler(){return new HandleWashExecutionCompletedHandler(appointments,inbox,transitions,pending,outbox);}
    HandleWashExecutionStartedHandler startedHandler(){return new HandleWashExecutionStartedHandler(appointments,inbox,transitions,pending,outbox);}
    HandleWashEntryRejectedHandler rejectedHandler(){return new HandleWashEntryRejectedHandler(appointments,inbox,transitions,pending,outbox);}
  }

  private static final class MemoryAppointments implements AppointmentRepository {
    final Map<UUID,Appointment> values=new HashMap<>(); Appointment get(UUID id){return values.get(id);} @Override public Appointment save(Appointment v){values.put(v.appointmentId(),v);return v;} @Override public Optional<Appointment> findById(UUID id){return Optional.ofNullable(values.get(id));} @Override public Optional<Appointment> findByIdForUpdate(UUID id){return findById(id);} @Override public boolean updateIfVersion(Appointment v,long expected){Appointment c=values.get(v.appointmentId());if(c==null||c.version()!=expected)return false;values.put(v.appointmentId(),v);return true;} @Override public long countActiveForStudent(UUID s,java.time.LocalDate d){return 0;} @Override public long countCompletedForStudent(UUID s,java.time.LocalDate d){return 0;} @Override public List<Appointment> findForSlot(UUID s,boolean l){return List.of();} @Override public List<Appointment> findForStudentAndDate(UUID s,java.time.LocalDate d){return List.of();} @Override public Optional<Appointment> findActiveByExceptionalAuthorizationForUpdate(UUID id){return Optional.empty();} @Override public List<Appointment> findByExceptionalAuthorization(UUID id){return List.of();} @Override public List<Appointment> findByStatus(AppointmentStatus s){return List.of();}
  }
  private static final class MemoryInbox implements ProcessedMessageRepository { final Map<UUID,String> states=new HashMap<>(); @Override public boolean tryClaim(UUID id,String t,Instant at){String s=states.get(id);if(s==null||"DEFERRED".equals(s)){states.put(id,"CLAIMED");return true;}return false;} @Override public boolean markApplied(UUID id,Instant at){states.put(id,"APPLIED");return true;} @Override public boolean markDeferred(UUID id,String r,Instant at){states.put(id,"DEFERRED");return true;} @Override public boolean wasProcessed(UUID id){return "APPLIED".equals(states.get(id));}}
  private static final class MemoryPending implements PendingWashExecutionEventRepository { final List<PendingEvent> events=new ArrayList<>(); @Override public void defer(PendingEvent e){if(events.stream().noneMatch(x->x.sourceEventId().equals(e.sourceEventId())))events.add(e);} @Override public List<PendingEvent> claim(int l,String w,Instant n,Instant u){return List.of();} @Override public boolean complete(UUID id,String w,Instant at){return true;} @Override public boolean discardBySourceEventId(UUID source,Instant at){return events.removeIf(e->e.sourceEventId().equals(source));} @Override public boolean hasOpenForAppointment(UUID appointment){return events.stream().anyMatch(e->e.appointmentId().equals(appointment));}}
  private static final class MemoryTransitions implements WashExecutionTransitionRepository { final List<TransitionRecord> values=new ArrayList<>(); @Override public boolean record(TransitionRecord t){values.add(t);return true;} @Override public Optional<TransitionRecord> find(UUID a,String t){return values.stream().filter(v->v.appointmentId().equals(a)&&v.eventType().equals(t)).max(Comparator.comparingLong(TransitionRecord::executionVersion));} @Override public Optional<TransitionRecord> findLatestForExecution(UUID e){return values.stream().filter(v->v.washExecutionId().equals(e)).max(Comparator.comparingLong(TransitionRecord::executionVersion));}}
  private static final class MemoryEvidence implements WashExecutionEvidenceRepository { final Map<UUID,EvidenceRecord> values=new HashMap<>(); @Override public EvidenceRecord recordArrival(EvidenceRecord v){values.put(v.appointmentId(),v);return v;} @Override public EvidenceRecord recordEntryAuthorization(EvidenceRecord v){values.put(v.appointmentId(),v);return v;} @Override public Optional<EvidenceRecord> findByAppointmentId(UUID id){return Optional.ofNullable(values.get(id));} @Override public Optional<EvidenceRecord> findByExecutionId(UUID id){return values.values().stream().filter(v->v.washExecutionId().equals(id)).findFirst();} @Override public boolean hasValidArrival(UUID id,Instant t){return false;}}
  private static final class MemoryOutbox implements OutboxRepository { final List<OutboxMessage> values=new ArrayList<>(); @Override public void append(OutboxMessage m){values.add(m);} @Override public List<OutboxMessage> claimBatch(int l,String w,Instant n,Instant u){return List.of();} @Override public boolean markPublished(UUID id,String w,Instant at){return true;} @Override public boolean markFailed(UUID id,String w,String e,Instant at){return true;}}
}
