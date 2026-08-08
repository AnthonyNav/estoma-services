package com.estoma.authentication.contracts.rpc;

import java.time.Instant;

/** Response for a successful {@code RegenerateTemporaryPassword} (commands.md §7.2). */
public record RegenerateTemporaryPasswordResponse(String temporaryPassword, Instant expiresAt) {}
