package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record TankRetiredPayload(UUID tankId, String status, int version, Instant occurredAt) {}
