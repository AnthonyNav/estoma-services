package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record ActivateCabinCommand(UUID cabinId, int expectedVersion) {}
