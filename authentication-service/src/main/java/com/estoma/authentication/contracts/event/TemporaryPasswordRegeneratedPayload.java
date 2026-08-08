package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of {@code TemporaryPasswordRegenerated} (events.md §4). Never includes the temporary password itself. */
public record TemporaryPasswordRegeneratedPayload(
    UUID accountId, boolean mustChangePassword, Instant expiresAt, Instant regeneratedAt) {}
