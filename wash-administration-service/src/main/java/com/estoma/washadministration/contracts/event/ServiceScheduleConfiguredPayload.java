package com.estoma.washadministration.contracts.event;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public record ServiceScheduleConfiguredPayload(
    UUID scheduleId,
    UUID calendarId,
    int dayOfWeek,
    LocalTime openingTime,
    LocalTime closingTime,
    int slotDurationMinutes,
    String status,
    int version,
    Instant occurredAt) {}
