package com.estoma.washadministration.contracts.consumed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccountAuthorizationSnapshot(
    UUID accountId,
    AuthorizationRoleSnapshot role,
    List<AuthorizationAccessSnapshot> systemAccesses,
    Instant snapshotGeneratedAt) {}
