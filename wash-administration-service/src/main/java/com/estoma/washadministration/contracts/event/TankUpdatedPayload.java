package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record TankUpdatedPayload(
    UUID tankId,
    UUID cabinId,
    String name,
    int configuredCapacity,
    String status,
    int version,
    Instant occurredAt) {}
