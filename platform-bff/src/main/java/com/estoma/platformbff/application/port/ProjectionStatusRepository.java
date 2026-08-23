package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.projection.ProjectionState;
import java.util.Optional;

public interface ProjectionStatusRepository {
  void upsert(ProjectionState state);

  Optional<ProjectionState> findByName(String projectionName);
}
