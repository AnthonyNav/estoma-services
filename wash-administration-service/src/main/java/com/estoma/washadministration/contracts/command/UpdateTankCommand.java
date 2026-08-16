package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record UpdateTankCommand(UUID tankId, String name, int configuredCapacity, int expectedVersion) {}
