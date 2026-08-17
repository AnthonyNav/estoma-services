package com.estoma.washadministration.contracts.consumed;

import java.time.Instant;
import java.util.List;

public record AuthorizationSnapshotPage(
    List<AccountAuthorizationSnapshot> items,
    String nextCursor,
    boolean hasMore,
    Instant snapshotGeneratedAt) {}
