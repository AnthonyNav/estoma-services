package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionRecoveryJobRepository {
  Optional<RecoveryJob> find(String projectionName);
  void start(RecoveryJob job);
  boolean advance(String projectionName, UUID generationId, UUID expectedCurrentQueryMessageId,
      String phase, UUID nextQueryMessageId, String cursor, String contextJson, Instant updatedAt);
  boolean complete(String projectionName, UUID generationId, UUID expectedCurrentQueryMessageId);
  void delete(String projectionName);

  record RecoveryJob(String projectionName, UUID generationId, String phase,
      UUID currentQueryMessageId, String cursor, String contextJson,
      Instant startedAt, Instant updatedAt) {}
}
