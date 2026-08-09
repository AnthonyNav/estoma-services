package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/**
 * Request for {@code RegenerateTemporaryPassword} (commands.md §7.2). Its
 * {@code idempotencyKey} must be distinct from the one used for {@code
 * SetTemporaryPassword}.
 */
public record RegenerateTemporaryPasswordRequest(
    UUID accountId, String idempotencyKey) {
  @Deprecated
  public RegenerateTemporaryPasswordRequest(UUID accountId, java.time.Instant ignoredExpiresAt, UUID ignoredRequestedBy, String idempotencyKey) {
    this(accountId, idempotencyKey);
  }
}
