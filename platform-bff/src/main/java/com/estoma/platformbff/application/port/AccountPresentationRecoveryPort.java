package com.estoma.platformbff.application.port;

import com.estoma.platformbff.application.projection.ProjectionReadModel;
import java.util.UUID;

/** Mutable local state used only by Authentication Account Presentation snapshot recovery. */
public interface AccountPresentationRecoveryPort {
  void clearAccountPresentationProjection();

  boolean hasAccountPresentation(UUID accountId);

  void upsertAccountPresentationSnapshot(ProjectionReadModel.AccountPresentation presentation);
}
