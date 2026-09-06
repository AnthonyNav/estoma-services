package com.estoma.appointments.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.PendingCapacityEvaluationRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import com.estoma.appointments.application.usecase.HandleWashExecutionCompletedHandler;
import com.estoma.appointments.application.usecase.PendingCapacityResolutionCoordinator;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.CancellationType;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CapacityCausalityIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");

  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AppointmentCapacityProjectionRepository capacity;
  @Autowired PendingCapacityEvaluationRepository pending;
  @Autowired WashExecutionTransitionRepository transitions;
  @Autowired HandleWashExecutionCompletedHandler completed;
  @Autowired PendingCapacityResolutionCoordinator capacityResolution;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("""
        TRUNCATE TABLE
          pending_capacity_evaluation, wash_execution_transition_projection,
          pending_wash_execution_events, processed_messages, outbox_messages,
          appointments, appointment_capacity_projections, appointment_time_slots
        CASCADE
        """).update();
  }

  @Test
  void cleaningReductionMatchesOnlyAfterCausalCompletionRemovesItsDemand() {
    UUID slotId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID causalAppointmentId = UUID.randomUUID();
    UUID otherAppointmentId = UUID.randomUUID();
    AppointmentTimeSlot slot = slot(slotId);
    slots.save(slot);

    Appointment causal = Appointment.scheduled(causalAppointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(600))
        .start(NOW.minusSeconds(120));
    Appointment other = Appointment.scheduled(otherAppointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(300));
    appointments.save(causal);
    appointments.save(other);

    // Cleaning already reduced projected shared capacity from two places to one. Destructive matching
    // is intentionally pending until the causal execution's completion is reflected locally.
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(
        slotId, 1, 0, 0, 2, NOW));
    UUID cleaningSourceEvent = UUID.randomUUID();
    pending.enqueue(new PendingCapacityEvaluationRepository.PendingEvaluation(
        UUID.randomUUID(), cleaningSourceEvent, slotId, executionId, causalAppointmentId,
        "CLEANING_STARTED", "NORMAL_COMPLETION",
        2, 1, 0, 0, 0, 0, 2,
        "PENDING", 0, NOW, null, null));

    UUID completionMessage = UUID.randomUUID();
    var result = completed.handle(new ExecutionEvent(
        completionMessage, completionMessage, executionId, causalAppointmentId,
        "WashExecutionCompleted", 3, NOW, null, NOW, UUID.randomUUID(),
        causal.startedAt(), null, null, null, "{}", UUID.randomUUID()));

    assertThat(result.status().name()).isEqualTo("APPLIED");
    assertThat(appointments.findById(causalAppointmentId).orElseThrow().status().name())
        .isEqualTo("COMPLETED");
    assertThat(appointments.findById(otherAppointmentId).orElseThrow().status().name())
        .isEqualTo("SCHEDULED");
    assertThat(jdbc.sql("SELECT status FROM pending_capacity_evaluation WHERE source_event_id=:source")
        .param("source", cleaningSourceEvent).query(String.class).single()).isEqualTo("COMPLETED");

    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentCompleted' AND aggregate_id=:id")
        .param("id", causalAppointmentId).query(Long.class).single()).isEqualTo(1);
    String reducedPayload = jdbc.sql("""
        SELECT payload::text FROM outbox_messages
        WHERE message_type='AppointmentTimeSlotCapacityReduced' AND aggregate_id=:slot
        """).param("slot", slotId).query(String.class).single();
    assertThat(reducedPayload).contains("\"affectedAppointmentsCount\": 0");
  }

  @Test
  void lateCapacityEvaluationAfterCompletionPreservesTerminalStateAndIsIdempotent() {
    UUID slotId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID causalAppointmentId = UUID.randomUUID();
    UUID otherAppointmentId = UUID.randomUUID();
    slots.save(slot(slotId));

    Appointment causal = Appointment.scheduled(causalAppointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(600))
        .start(NOW.minusSeconds(120));
    appointments.save(causal);
    appointments.save(Appointment.scheduled(otherAppointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(300)));
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(
        slotId, 1, 0, 0, 2, NOW));

    UUID completionMessage = UUID.randomUUID();
    completed.handle(new ExecutionEvent(
        completionMessage, completionMessage, executionId, causalAppointmentId,
        "WashExecutionCompleted", 3, NOW, null, NOW, UUID.randomUUID(),
        causal.startedAt(), null, null, null, "{}", UUID.randomUUID()));

    UUID cleaningSourceEvent = UUID.randomUUID();
    pending.enqueue(new PendingCapacityEvaluationRepository.PendingEvaluation(
        UUID.randomUUID(), cleaningSourceEvent, slotId, executionId, causalAppointmentId,
        "CLEANING_STARTED", "NORMAL_COMPLETION",
        2, 1, 0, 0, 0, 0, 2,
        "PENDING", 0, NOW.plusSeconds(1), null, null));
    CommandMetadata metadata = new CommandMetadata(UUID.randomUUID(), cleaningSourceEvent.toString(),
        UUID.randomUUID(), cleaningSourceEvent, ActorContext.system("appointments"), NOW.plusSeconds(1));

    assertThat(capacityResolution.resolve(executionId, "COMPLETED", metadata, NOW.plusSeconds(1)))
        .isEqualTo(1);
    assertThat(capacityResolution.resolve(executionId, "COMPLETED", metadata, NOW.plusSeconds(2)))
        .isZero();
    assertThat(appointments.findById(causalAppointmentId).orElseThrow().status().name())
        .isEqualTo("COMPLETED");
    assertThat(appointments.findById(otherAppointmentId).orElseThrow().status().name())
        .isEqualTo("SCHEDULED");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentCompleted' AND aggregate_id=:id")
        .param("id", causalAppointmentId).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentTimeSlotCapacityReduced' AND aggregate_id=:id")
        .param("id", slotId).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void staleCompletionAfterNewerCancellationCannotResolveCompletionCapacity() {
    UUID slotId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    slots.save(slot(slotId));
    Appointment cancelled = Appointment.scheduled(appointmentId, slotId, UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW.minusSeconds(600))
        .start(NOW.minusSeconds(120))
        .cancel(CancellationType.CLINIC, "CAPACITY_LOSS", "clinic capacity loss",
            UUID.randomUUID(), NOW);
    appointments.save(cancelled);
    capacity.save(new AppointmentCapacityProjectionRepository.CapacityProjection(
        slotId, 0, 0, 0, 2, NOW));
    UUID cancellationSource = UUID.randomUUID();
    transitions.record(new WashExecutionTransitionRepository.TransitionRecord(
        UUID.randomUUID(), executionId, appointmentId, "WashExecutionCancelled", 5,
        cancellationSource, "CANCELLED", NOW, NOW));

    UUID cleaningSourceEvent = UUID.randomUUID();
    pending.enqueue(new PendingCapacityEvaluationRepository.PendingEvaluation(
        UUID.randomUUID(), cleaningSourceEvent, slotId, executionId, appointmentId,
        "CLEANING_STARTED", "NORMAL_COMPLETION",
        1, 0, 0, 0, 0, 0, 2,
        "PENDING", 0, NOW, null, null));

    UUID staleCompletion = UUID.randomUUID();
    completed.handle(new ExecutionEvent(
        staleCompletion, staleCompletion, executionId, appointmentId,
        "WashExecutionCompleted", 4, NOW.plusSeconds(1), null, NOW.plusSeconds(1),
        UUID.randomUUID(), cancelled.startedAt(), null, null, null, "{}", UUID.randomUUID()));

    assertThat(appointments.findById(appointmentId).orElseThrow().status().name())
        .isEqualTo("CANCELLED");
    assertThat(jdbc.sql("SELECT status FROM pending_capacity_evaluation WHERE source_event_id=:id")
        .param("id", cleaningSourceEvent).query(String.class).single()).isEqualTo("PENDING");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type IN ('AppointmentCompleted','AppointmentTimeSlotCapacityReduced')")
        .query(Long.class).single()).isZero();
  }

  private static AppointmentTimeSlot slot(UUID id) {
    Instant starts = NOW.plusSeconds(3600);
    return AppointmentTimeSlot.create(id, UUID.randomUUID(), UUID.randomUUID(),
        LocalDate.ofInstant(starts, ZoneOffset.UTC), starts, starts.plusSeconds(3600), ZoneOffset.UTC,
        starts.minusSeconds(600), starts.minusSeconds(1200), starts.plusSeconds(600));
  }
}
