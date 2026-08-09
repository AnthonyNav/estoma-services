package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record EmailDeliveryIndeterminateResolvedPayload(UUID emailDeliveryId, UUID communicationId, String resolution, Instant resolvedAt) {}
