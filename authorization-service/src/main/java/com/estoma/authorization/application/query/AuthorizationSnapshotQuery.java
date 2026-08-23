package com.estoma.authorization.application.query;

import com.estoma.authorization.domain.model.SystemCode;
import java.util.UUID;

/** Broker-neutral commands to answer Authorization's two authoritative snapshot Queries. */
public sealed interface AuthorizationSnapshotQuery
    permits AuthorizationSnapshotQuery.GetAccountAuthorizationSnapshot,
        AuthorizationSnapshotQuery.ListAccountAuthorizationSnapshotsPage {

  record GetAccountAuthorizationSnapshot(UUID accountId, SystemCode systemCode)
      implements AuthorizationSnapshotQuery {}

  record ListAccountAuthorizationSnapshotsPage(String cursor, int limit, SystemCode systemCode)
      implements AuthorizationSnapshotQuery {}
}
