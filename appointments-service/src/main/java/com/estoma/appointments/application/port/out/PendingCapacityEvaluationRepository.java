package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PendingCapacityEvaluationRepository {
  void enqueue(PendingEvaluation evaluation);
  List<PendingEvaluation> claim(int limit, String workerId, Instant now, Instant leaseUntil);
  boolean complete(UUID evaluationId, String workerId, Instant completedAt);
  record PendingEvaluation(UUID evaluationId, UUID slotId, String cause,
      String status, int attempts, Instant availableAt, String claimedBy, Instant leaseUntil) {}
}
