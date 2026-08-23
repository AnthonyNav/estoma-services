package com.estoma.appointments.application.port.out;

import java.time.Instant;

public interface ProjectionRecoveryRepository {
  RecoveryState get(String projectionName);
  boolean update(RecoveryState state, long expectedVersion);
  record RecoveryState(String projectionName, String status, long sourceVersion,
      Instant updatedAt, String lastError, long version) {}
}
