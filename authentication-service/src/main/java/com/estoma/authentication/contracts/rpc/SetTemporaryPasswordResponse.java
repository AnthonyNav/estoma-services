package com.estoma.authentication.contracts.rpc;

import java.time.Instant;

/**
 * Response for a successful {@code SetTemporaryPassword} (commands.md
 * §7.1): the generated secret, delivered exactly once. Never persisted in
 * plain text anywhere.
 */
public record SetTemporaryPasswordResponse(String temporaryPassword, Instant expiresAt) {}
