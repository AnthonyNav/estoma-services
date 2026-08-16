package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record AdministrativeClosureScheduledPayload(
    UUID closureId,
    UUID calendarId,
    String scope,
    UUID cabinId,
    UUID tankId,
    Instant startsAt,
    Instant endsAt,
    String reason,
    String status,
    int version,
    Instant occurredAt) {}
