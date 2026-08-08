package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** {@code EmailDeliveryRetryScheduled} (events.md §2, §4). */
public record EmailDeliveryRetryScheduledPayload(
    UUID emailDeliveryId, UUID communicationId, int attemptNumber, String normalizedErrorCode, Instant nextAttemptAt, Instant scheduledAt) {}
