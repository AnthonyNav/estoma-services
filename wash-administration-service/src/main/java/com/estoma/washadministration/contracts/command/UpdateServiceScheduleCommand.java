package com.estoma.washadministration.contracts.command;

import java.time.LocalTime;
import java.util.UUID;

public record UpdateServiceScheduleCommand(
    UUID scheduleId,
    int dayOfWeek,
    LocalTime openingTime,
    LocalTime closingTime,
    int slotDurationMinutes,
    int expectedVersion) {}
