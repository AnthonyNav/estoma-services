package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record DeactivateTankCommand(UUID tankId, String reason, int expectedVersion) {}
