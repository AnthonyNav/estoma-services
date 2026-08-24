package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MissedEvaluationRepository {
  RequestDecision ensureQuery(UUID appointmentId, long expectedAppointmentVersion,
      UUID proposedQueryMessageId, Instant now);
  Optional<Evaluation> find(UUID appointmentId, long expectedAppointmentVersion);
  Optional<Evaluation> findByQueryMessageId(UUID queryMessageId);
  boolean confirm(UUID appointmentId, long expectedAppointmentVersion, UUID queryMessageId,
      UUID sourceResultMessageId, Instant snapshotAt, Instant arrivedAt, Instant updatedAt);

  record RequestDecision(Evaluation evaluation, boolean queryCreated) {}

  record Evaluation(UUID appointmentId, long expectedAppointmentVersion,
      UUID currentQueryMessageId, String status, Instant snapshotAt, Instant arrivedAt,
      UUID sourceResultMessageId, Instant updatedAt) {
    public boolean confirmedAfter(Instant instant) {
      return "CONFIRMED".equals(status) && snapshotAt != null && !snapshotAt.isBefore(instant);
    }
  }
}
