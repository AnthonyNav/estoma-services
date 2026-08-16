package com.estoma.washadministration.contracts.consumed;

import java.time.Instant;
import java.util.UUID;

public record AccountRoleAssignedPayload(
    UUID accountId, UUID roleId, String roleCode, Instant assignedAt) {}
