package com.estoma.washadministration.contracts.command;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateServiceCalendarCommand(
    UUID calendarId,
    String name,
    String timezone,
    LocalDate effectiveFrom,
    LocalDate effectiveUntil,
    int expectedVersion) {}
