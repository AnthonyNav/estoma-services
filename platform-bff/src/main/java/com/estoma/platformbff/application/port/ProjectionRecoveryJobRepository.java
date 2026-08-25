package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.projection.RecoveryJob;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionRecoveryJobRepository {
  void insert(RecoveryJob job);

  void update(RecoveryJob job);

  long nextGeneration(String projectionName);

  Optional<RecoveryJob> findById(UUID recoveryJobId);
}
