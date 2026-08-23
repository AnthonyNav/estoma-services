package com.estoma.platformbff.domain.idempotency;

import java.time.Instant;
import java.util.UUID;

public record IdempotencyRecord(
    String scopeKey,
    UUID actorAccountId,
    String publicOperation,
    String idempotencyKey,
    String requestFingerprint,
    UUID operationId,
    Instant createdAt,
    Instant expiresAt) {
}
