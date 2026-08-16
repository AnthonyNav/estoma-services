package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ServiceCalendarUpdatedPayload(
    UUID calendarId,
    String name,
    String timezone,
    LocalDate effectiveFrom,
    LocalDate effectiveUntil,
    String status,
    int version,
    Instant occurredAt) {}
