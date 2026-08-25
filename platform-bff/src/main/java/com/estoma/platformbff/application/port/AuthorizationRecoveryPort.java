package com.estoma.platformbff.application.port;

import com.estoma.platformbff.application.projection.ProjectionReadModel;
import java.util.UUID;

public interface AuthorizationRecoveryPort {
  void clearAuthorizationProjection();

  boolean hasAuthorizationState(UUID accountId);

  void upsertSnapshot(ProjectionReadModel.AuthorizationRole role,
      java.util.List<ProjectionReadModel.AuthorizationAccess> accesses);
}
