package com.estoma.washadministration.contracts.command;

import java.time.LocalTime;
import java.util.UUID;

public record ConfigureServiceScheduleCommand(
    UUID scheduleId,
    UUID calendarId,
    int dayOfWeek,
    LocalTime openingTime,
    LocalTime closingTime,
    int slotDurationMinutes) {}
