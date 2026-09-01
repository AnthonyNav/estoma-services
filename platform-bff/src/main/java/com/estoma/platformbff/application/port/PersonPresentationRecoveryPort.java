package com.estoma.platformbff.application.port;

import com.estoma.platformbff.application.projection.ProjectionReadModel;

/** Mutable local state used only by Users Person Presentation snapshot recovery. */
public interface PersonPresentationRecoveryPort {
  void clearPersonPresentationProjection();

  void upsertPersonPresentationSnapshot(ProjectionReadModel.Person presentation);
}
