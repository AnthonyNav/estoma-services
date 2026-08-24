package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PendingCapacityEvaluationRepository {
  void enqueue(PendingEvaluation evaluation);
  List<PendingEvaluation> claim(int limit, String workerId, Instant now, Instant leaseUntil);
  List<PendingEvaluation> claimForExecution(UUID washExecutionId, int limit, String workerId,
      Instant now, Instant leaseUntil);
  boolean complete(UUID evaluationId, String workerId, Instant completedAt);

  record PendingEvaluation(UUID evaluationId, UUID sourceEventId, UUID slotId,
      UUID washExecutionId, UUID appointmentId, String cause, String cleaningTrigger,
      Integer previousNormalCapacity, Integer currentNormalCapacity,
      Integer previousJourneyCapacity, Integer currentJourneyCapacity,
      Integer previousImmunocompromisedCapacity, Integer currentImmunocompromisedCapacity,
      long projectionVersion, String status, int attempts, Instant availableAt,
      String claimedBy, Instant leaseUntil) {
    public PendingEvaluation {
      if (evaluationId == null || sourceEventId == null || slotId == null || washExecutionId == null
          || appointmentId == null || cause == null || status == null || availableAt == null
          || projectionVersion <= 0) {
        throw new IllegalArgumentException("invalid pending capacity evaluation");
      }
    }

    /** Compatibility shape for pre-PR4 tests/technical rows that had no reduction snapshot. */
    public PendingEvaluation(UUID evaluationId, UUID sourceEventId, UUID slotId,
        UUID washExecutionId, UUID appointmentId, String cause, String cleaningTrigger,
        String status, int attempts, Instant availableAt, String claimedBy, Instant leaseUntil) {
      this(evaluationId, sourceEventId, slotId, washExecutionId, appointmentId, cause, cleaningTrigger,
          null, null, null, null, null, null, 1L, status, attempts, availableAt, claimedBy, leaseUntil);
    }

    public boolean hasReductionSnapshot() {
      return previousNormalCapacity != null && currentNormalCapacity != null
          && previousJourneyCapacity != null && currentJourneyCapacity != null
          && previousImmunocompromisedCapacity != null && currentImmunocompromisedCapacity != null;
    }
  }
}
