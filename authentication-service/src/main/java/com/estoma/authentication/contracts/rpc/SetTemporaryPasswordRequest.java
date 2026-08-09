package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/**
 * Request for {@code SetTemporaryPassword} (commands.md §7.1), restricted
 * to an authorized administrative actor. {@code idempotencyKey} is
 * mandatory and protected by {@code security_rpc_idempotency_records}.
 */
public record SetTemporaryPasswordRequest(
    UUID accountId, UUID requestedByAccountId, String idempotencyKey) {}
