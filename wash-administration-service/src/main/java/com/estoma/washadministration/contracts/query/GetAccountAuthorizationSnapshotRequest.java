package com.estoma.washadministration.contracts.query;

import java.util.UUID;

public record GetAccountAuthorizationSnapshotRequest(UUID accountId, String systemCode) {}
