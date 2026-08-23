package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.projection.RecoveryJob;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionRecoveryJobRepository {
  void insert(RecoveryJob job);

  Optional<RecoveryJob> findById(UUID recoveryJobId);
}
