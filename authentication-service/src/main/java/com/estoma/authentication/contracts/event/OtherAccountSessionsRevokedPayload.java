package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of {@code OtherAccountSessionsRevoked}; the initiating session remains valid. */
public record OtherAccountSessionsRevokedPayload(
    UUID accountId,
    UUID currentSessionId,
    String reasonCode,
    Instant revokedAt) {}
