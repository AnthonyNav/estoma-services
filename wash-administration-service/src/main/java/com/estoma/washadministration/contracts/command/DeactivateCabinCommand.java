package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record DeactivateCabinCommand(UUID cabinId, String reason, int expectedVersion) {}
