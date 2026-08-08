package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code StudentLoginIdentifierSynchronizationFailed} (events.md
 * §3.1). Mandatory, not optional, whenever {@code StudentNumberCorrected}
 * hits a definitive functional conflict. Never includes credentials,
 * tokens, or the full {@code loginIdentifier}.
 */
public record StudentLoginIdentifierSynchronizationFailedPayload(
    UUID accountId,
    UUID studentAcademicProfileId,
    int studentNumberVersion,
    String errorCode,
    Instant failedAt,
    UUID correlationId,
    UUID causationId) {}
