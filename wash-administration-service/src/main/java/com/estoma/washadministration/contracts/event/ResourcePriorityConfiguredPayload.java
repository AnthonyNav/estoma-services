package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record ResourcePriorityConfiguredPayload(
    UUID resourcePriorityId,
    String appointmentType,
    String cabinType,
    String usageMode,
    int priorityOrder,
    String status,
    int version,
    Instant occurredAt) {}
