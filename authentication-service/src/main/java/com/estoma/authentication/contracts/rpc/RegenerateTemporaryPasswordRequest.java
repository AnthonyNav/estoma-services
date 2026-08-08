package com.estoma.authentication.contracts.rpc;

import java.time.Instant;
import java.util.UUID;

/**
 * Request for {@code RegenerateTemporaryPassword} (commands.md §7.2). Its
 * {@code idempotencyKey} must be distinct from the one used for {@code
 * SetTemporaryPassword}.
 */
public record RegenerateTemporaryPasswordRequest(
    UUID accountId, Instant expiresAt, UUID requestedByAccountId, String idempotencyKey) {}
