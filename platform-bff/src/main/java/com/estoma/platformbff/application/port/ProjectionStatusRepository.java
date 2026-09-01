package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.projection.ProjectionState;
import java.time.Instant;
import java.util.Optional;

public interface ProjectionStatusRepository {
  void upsert(ProjectionState state);

  Optional<ProjectionState> findByName(String projectionName);

  /** Atomically reserves a pristine recoverable projection for its first full snapshot. */
  boolean claimBootstrapping(String projectionName, long expectedGeneration, Instant now);
}
