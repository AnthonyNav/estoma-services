package com.estoma.users.contract;

import java.util.UUID;

/**
 * The actor an envelope carries responsibility for. Authorization checks
 * must use this — never data taken from the payload
 * (security-observability.md:156, docs/system-contract.md §7 step b).
 */
public record Actor(UUID accountId) {}
