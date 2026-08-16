package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record ActivateServiceCalendarCommand(UUID calendarId, int expectedVersion) {}
