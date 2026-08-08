package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code PasswordRecoveryRequested} (events.md §7). Consumed by
 * {@code communications-service}. Never includes the full recovery token or
 * code.
 */
public record PasswordRecoveryRequestedPayload(
    UUID recoveryId, UUID accountId, UUID personId, Instant expiresAt, Instant requestedAt) {}
