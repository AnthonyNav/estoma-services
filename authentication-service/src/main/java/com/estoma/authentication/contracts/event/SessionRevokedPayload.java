package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of {@code SessionRevoked} (events.md §6). Repeated revocation must not produce duplicate effects. */
public record SessionRevokedPayload(UUID sessionId, UUID accountId, String reasonCode, Instant revokedAt) {}
