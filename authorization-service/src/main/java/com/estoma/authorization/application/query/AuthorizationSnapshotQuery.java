package com.estoma.authorization.application.query;

import com.estoma.authorization.domain.model.SystemCode;
import java.util.UUID;

/** Broker-neutral commands to answer Authorization's two authoritative snapshot Queries. */
public sealed interface AuthorizationSnapshotQuery
    permits AuthorizationSnapshotQuery.GetAccountAuthorizationSnapshot,
        AuthorizationSnapshotQuery.ListAccountAuthorizationSnapshotsPage,
        AuthorizationSnapshotQuery.GetSystemCatalogSnapshot,
        AuthorizationSnapshotQuery.ListSystemCatalogSnapshotsPage {

  record GetAccountAuthorizationSnapshot(UUID accountId, SystemCode systemCode)
      implements AuthorizationSnapshotQuery {}

  record ListAccountAuthorizationSnapshotsPage(String cursor, int limit, SystemCode systemCode)
      implements AuthorizationSnapshotQuery {}

  record GetSystemCatalogSnapshot(SystemCode systemCode)
      implements AuthorizationSnapshotQuery {}

  record ListSystemCatalogSnapshotsPage(String cursor, int limit)
      implements AuthorizationSnapshotQuery {}
}
