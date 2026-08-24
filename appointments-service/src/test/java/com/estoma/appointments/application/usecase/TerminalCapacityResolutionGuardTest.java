package com.estoma.appointments.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentStatus;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.CancellationType;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerminalCapacityResolutionGuardTest {
  private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void staleCompletionAfterNewerCancellationDoesNotResolveCompletedCapacity() {
    Fixture fixture = new Fixture();
    UUID executionId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    Appointment cancelled = scheduled(appointmentId).cancel(CancellationType.CLINIC,
        "CAPACITY_LOSS", "clinic capacity loss", UUID.randomUUID(), NOW.plusSeconds(60));

    UUID sourceEventId = UUID.randomUUID();
    fixture.claim(sourceEventId, "WashExecutionCompleted");
    fixture.latest(executionId, appointmentId, "WashExecutionCancelled", 5, "CANCELLED");
    when(fixture.appointments.findById(appointmentId)).thenReturn(Optional.of(cancelled));

    ApplicationResult result = fixture.completedHandler().handle(new ExecutionEvent(
        UUID.randomUUID(), sourceEventId, executionId, appointmentId, "WashExecutionCompleted", 4,
        NOW.plusSeconds(90), null, NOW.plusSeconds(90), UUID.randomUUID(), null,
        null, null, "stale completion", "{}"));

    assertThat(result.status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(cancelled.status()).isEqualTo(AppointmentStatus.CANCELLED);
    verify(fixture.capacityResolution, never()).resolve(any(), anyString(), any(), any());
  }

  @Test
  void staleCancellationAfterNewerCompletionDoesNotResolveCancelledCapacity() {
    Fixture fixture = new Fixture();
    UUID executionId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    Appointment completed = scheduled(appointmentId)
        .start(NOW.plusSeconds(20))
        .complete(UUID.randomUUID(), NOW.plusSeconds(80));

    UUID sourceEventId = UUID.randomUUID();
    fixture.claim(sourceEventId, "WashExecutionCancelled");
    fixture.latest(executionId, appointmentId, "WashExecutionCompleted", 6, "COMPLETED");
    when(fixture.appointments.findById(appointmentId)).thenReturn(Optional.of(completed));

    ApplicationResult result = fixture.cancelledHandler().handle(new ExecutionEvent(
        UUID.randomUUID(), sourceEventId, executionId, appointmentId, "WashExecutionCancelled", 5,
        NOW.plusSeconds(70), null, NOW.plusSeconds(70), UUID.randomUUID(), NOW.plusSeconds(20),
        "CLINIC", "CAPACITY_LOSS", "stale cancellation", "{}"));

    assertThat(result.status()).isEqualTo(ApplicationResult.Status.APPLIED);
    assertThat(completed.status()).isEqualTo(AppointmentStatus.COMPLETED);
    verify(fixture.capacityResolution, never()).resolve(any(), anyString(), any(), any());
  }

  private static Appointment scheduled(UUID appointmentId) {
    return Appointment.scheduled(appointmentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, null, NOW);
  }

  private static final class Fixture {
    final AppointmentRepository appointments = mock(AppointmentRepository.class);
    final ProcessedMessageRepository inbox = mock(ProcessedMessageRepository.class);
    final WashExecutionTransitionRepository transitions = mock(WashExecutionTransitionRepository.class);
    final PendingWashExecutionEventRepository pending = mock(PendingWashExecutionEventRepository.class);
    final OutboxRepository outbox = mock(OutboxRepository.class);
    final PendingCapacityResolutionCoordinator capacityResolution = mock(PendingCapacityResolutionCoordinator.class);

    void claim(UUID sourceEventId, String eventType) {
      when(inbox.tryClaim(eq(sourceEventId), eq(eventType), any(Instant.class))).thenReturn(true);
    }

    void latest(UUID executionId, UUID appointmentId, String eventType, long version, String state) {
      when(transitions.findLatestForExecution(executionId)).thenReturn(Optional.of(
          new WashExecutionTransitionRepository.TransitionRecord(UUID.randomUUID(), executionId,
              appointmentId, eventType, version, UUID.randomUUID(), state, NOW, NOW)));
    }

    HandleWashExecutionCompletedHandler completedHandler() {
      return new HandleWashExecutionCompletedHandler(appointments, inbox, transitions, pending, outbox,
          capacityResolution);
    }

    HandleWashExecutionCancelledHandler cancelledHandler() {
      return new HandleWashExecutionCancelledHandler(appointments, inbox, transitions, pending, outbox,
          capacityResolution);
    }
  }
}
