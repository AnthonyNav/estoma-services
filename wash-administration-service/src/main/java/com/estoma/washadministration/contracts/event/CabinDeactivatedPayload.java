package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record CabinDeactivatedPayload(UUID cabinId, String status, int version, Instant occurredAt) {}
