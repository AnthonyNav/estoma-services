package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingCapacityEvaluationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Resolves CleaningPeriodStarted matching only after the causal Appointment reaches its terminal state. */
@Service
public class PendingCapacityResolutionCoordinator {
  private final PendingCapacityEvaluationRepository pending;
  private final AppointmentRepository appointments;
  private final CapacityMatchingCoordinator matching;
  private final OutboxRepository outbox;

  public PendingCapacityResolutionCoordinator(PendingCapacityEvaluationRepository pending,
      AppointmentRepository appointments, CapacityMatchingCoordinator matching, OutboxRepository outbox) {
    this.pending = pending;
    this.appointments = appointments;
    this.matching = matching;
    this.outbox = outbox;
  }

  public int resolve(UUID washExecutionId, String expectedAppointmentStatus,
      CommandMetadata metadata, Instant now) {
    String worker = UUID.randomUUID().toString();
    var rows = pending.claimForExecution(washExecutionId, 100, worker, now, now.plusSeconds(30));
    int resolved = 0;
    for (var row : rows) {
      var causal = appointments.findByIdForUpdate(row.appointmentId())
          .orElseThrow(() -> new IllegalStateException("pending capacity causal appointment disappeared"));
      if (!expectedAppointmentStatus.equals(causal.status().name())) {
        throw new IllegalStateException("pending capacity evaluation observed non-terminal causal state");
      }

      int cancelled = matching.reconcile(row.slotId(), metadata, now);
      if (row.hasReductionSnapshot() && reduced(row)) {
        ApplicationSupport.appendEvent(outbox, metadata, "AppointmentTimeSlotCapacityReduced",
            row.slotId(), row.projectionVersion(),
            AppointmentEventPayloads.capacityReduced(row.slotId(),
                row.previousNormalCapacity(), row.currentNormalCapacity(),
                row.previousJourneyCapacity(), row.currentJourneyCapacity(),
                row.previousImmunocompromisedCapacity(), row.currentImmunocompromisedCapacity(),
                cancelled, row.projectionVersion()), now);
      }
      if (!pending.complete(row.evaluationId(), worker, now)) {
        throw new IllegalStateException("pending capacity evaluation lost its claim before completion");
      }
      resolved++;
    }
    return resolved;
  }

  private static boolean reduced(PendingCapacityEvaluationRepository.PendingEvaluation row) {
    return row.currentNormalCapacity() < row.previousNormalCapacity()
        || row.currentJourneyCapacity() < row.previousJourneyCapacity()
        || row.currentImmunocompromisedCapacity() < row.previousImmunocompromisedCapacity();
  }
}
