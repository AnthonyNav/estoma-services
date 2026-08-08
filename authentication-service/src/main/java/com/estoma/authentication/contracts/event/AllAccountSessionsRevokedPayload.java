package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code AllAccountSessionsRevoked} (events.md §6). May
 * originate from deactivation, password change/recovery, suspicious
 * activity, or an administrative request.
 */
public record AllAccountSessionsRevokedPayload(UUID accountId, String reason, Instant revokedAt) {}
