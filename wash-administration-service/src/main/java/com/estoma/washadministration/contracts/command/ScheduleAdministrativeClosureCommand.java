package com.estoma.washadministration.contracts.command;

import java.time.Instant;
import java.util.UUID;

public record ScheduleAdministrativeClosureCommand(
    UUID closureId,
    UUID calendarId,
    String scope,
    UUID cabinId,
    UUID tankId,
    Instant startsAt,
    Instant endsAt,
    String reason) {}
