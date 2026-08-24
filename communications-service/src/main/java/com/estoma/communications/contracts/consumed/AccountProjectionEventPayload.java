package com.estoma.communications.contracts.consumed;

import java.util.UUID;

/**
 * Common subset carried by Authentication's AccountCreated/Activated/Deactivated events.
 * Ordering comes from the canonical envelope aggregateVersion, not a consumer-specific
 * sourceVersion field invented in the payload.
 */
public record AccountProjectionEventPayload(UUID accountId, UUID personId, String status) {}
