package com.estoma.communications.contracts.consumed;

import java.time.Instant;
import java.util.UUID;

/** Canonical non-secret payload published by Authentication for password recovery delivery. */
public record PasswordRecoveryRequestedPayload(
    UUID recoveryId,
    UUID accountId,
    UUID personId,
    Instant expiresAt,
    Instant requestedAt) {}
