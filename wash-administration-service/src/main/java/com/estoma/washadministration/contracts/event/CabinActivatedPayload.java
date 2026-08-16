package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record CabinActivatedPayload(UUID cabinId, String status, int version, Instant occurredAt) {}
