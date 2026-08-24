package com.estoma.platformbff.application.port;

import com.estoma.platformbff.application.projection.ProjectionReadModel;

public interface ProjectionReadModelPort {
  void upsert(ProjectionReadModel model);
}
