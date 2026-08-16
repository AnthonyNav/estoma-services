package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record DeactivateServiceScheduleCommand(UUID scheduleId, String reason, int expectedVersion) {}
