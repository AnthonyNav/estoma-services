package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** {@code EmailDeliveryFailed}: the email terminated in definitive failure (events.md §2, §4). */
public record EmailDeliveryFailedPayload(
    UUID emailDeliveryId, UUID communicationId, UUID recipientAccountId, int attemptCount, String normalizedErrorCode, Instant failedAt) {}
