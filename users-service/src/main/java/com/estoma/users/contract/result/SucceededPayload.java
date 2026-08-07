package com.estoma.users.contract.result;

import java.util.UUID;

/**
 * Shared payload shape for all four {@code <Command>Succeeded} results
 * (docs/system-contract.md §4). {@code version} is the aggregate version
 * after the change, matching the derived event's {@code aggregateVersion}.
 */
public record SucceededPayload(UUID personId, int version) {}
