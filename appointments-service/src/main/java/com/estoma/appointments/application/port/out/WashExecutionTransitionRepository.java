package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WashExecutionTransitionRepository {
  boolean record(TransitionRecord transition);
  Optional<TransitionRecord> find(UUID appointmentId, String eventType);
  Optional<TransitionRecord> findLatestForExecution(UUID washExecutionId);

  record TransitionRecord(UUID transitionId, UUID washExecutionId, UUID appointmentId,
      String eventType, long executionVersion, UUID sourceEventId, String state,
      Instant occurredAt, Instant appliedAt) {
    public TransitionRecord {
      if (transitionId == null || washExecutionId == null || appointmentId == null || eventType == null
          || executionVersion <= 0 || sourceEventId == null || state == null || occurredAt == null
          || appliedAt == null) throw new IllegalArgumentException("invalid transition");
    }
  }
}
