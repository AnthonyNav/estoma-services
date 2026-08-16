package com.estoma.washadministration.contracts.consumed;

import java.time.Instant;
import java.util.UUID;

public record AccountRoleChangedPayload(
    UUID accountId,
    UUID previousRoleId,
    String previousRoleCode,
    UUID newRoleId,
    String newRoleCode,
    Instant changedAt) {}
