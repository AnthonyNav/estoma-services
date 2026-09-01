package com.estoma.platformbff.application.port;

import com.estoma.platformbff.application.projection.ProjectionReadModel;

/** Mutable local state used only by Authorization System catalog snapshot recovery. */
public interface SystemCatalogRecoveryPort {
  void clearSystemCatalogProjection();

  void markSystemCatalogUntrusted(String systemCode);

  void upsertSystemCatalogSnapshot(ProjectionReadModel.SystemCatalog catalog);
}
