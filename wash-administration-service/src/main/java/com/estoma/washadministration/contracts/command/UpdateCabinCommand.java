package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record UpdateCabinCommand(UUID cabinId, String name, String cabinType, int expectedVersion) {}
