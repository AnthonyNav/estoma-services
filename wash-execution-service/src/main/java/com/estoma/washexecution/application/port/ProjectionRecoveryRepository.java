package com.estoma.washexecution.application.port;

import java.time.Instant;
import java.util.Optional;

public interface ProjectionRecoveryRepository {
  Optional<RecoveryState> find(String projectionName);
  void mark(String projectionName, String source, String status, String error, Instant updatedAt);

  record RecoveryState(String projectionName, String source, String status, String error, Instant updatedAt) {}
}
