package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record CabinRegisteredPayload(
    UUID cabinId, String code, String name, String cabinType, String status, int version, Instant occurredAt) {}
