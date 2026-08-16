package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record TankRegisteredPayload(
    UUID tankId,
    UUID cabinId,
    String code,
    String name,
    int configuredCapacity,
    String status,
    int version,
    Instant occurredAt) {}
