package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/**
 * Request for {@code RegenerateTemporaryPassword} (commands.md §7.2). Its
 * {@code idempotencyKey} must be distinct from the one used for {@code
 * SetTemporaryPassword}.
 */
public record RegenerateTemporaryPasswordRequest(
    UUID accountId, UUID requestedByAccountId, String idempotencyKey) {}
