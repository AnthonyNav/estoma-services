package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record DeactivateServiceCalendarCommand(UUID calendarId, String reason, int expectedVersion) {}
