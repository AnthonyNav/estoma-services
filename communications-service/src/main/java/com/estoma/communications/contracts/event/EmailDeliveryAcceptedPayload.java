package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** {@code EmailDeliveryAccepted} (events.md §2, §4): the provider accepted the send request, not physical delivery. */
public record EmailDeliveryAcceptedPayload(
    UUID emailDeliveryId,
    UUID communicationId,
    UUID recipientAccountId,
    String provider,
    String deliveryMode,
    String providerMessageId,
    int attemptNumber,
    Instant acceptedAt) {}
