package com.estoma.washadministration.contracts.command;

import java.time.LocalDate;
import java.util.UUID;

public record CreateServiceCalendarCommand(
    UUID calendarId,
    String code,
    String name,
    String timezone,
    LocalDate effectiveFrom,
    LocalDate effectiveUntil) {}
