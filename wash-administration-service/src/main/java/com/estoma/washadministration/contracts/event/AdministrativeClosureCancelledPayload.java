package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record AdministrativeClosureCancelledPayload(
    UUID closureId,
    String status,
    int version,
    Instant cancelledAt,
    Instant occurredAt) {}
