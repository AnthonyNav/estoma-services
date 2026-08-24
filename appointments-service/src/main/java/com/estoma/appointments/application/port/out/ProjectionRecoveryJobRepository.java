package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionRecoveryJobRepository {
  Optional<RecoveryJob> find(String projectionName);
  boolean tryStart(RecoveryJob job);
  default void start(RecoveryJob job) {
    if (!tryStart(job)) throw new IllegalStateException("recovery job already exists for " + job.projectionName());
  }
  boolean advance(String projectionName, UUID generationId, UUID expectedCurrentQueryMessageId,
      String phase, UUID nextQueryMessageId, String cursor, String contextJson, Instant updatedAt);
  boolean complete(String projectionName, UUID generationId, UUID expectedCurrentQueryMessageId);
  void delete(String projectionName);

  record RecoveryJob(String projectionName, UUID generationId, String phase,
      UUID currentQueryMessageId, String cursor, String contextJson,
      Instant startedAt, Instant updatedAt) {}
}
