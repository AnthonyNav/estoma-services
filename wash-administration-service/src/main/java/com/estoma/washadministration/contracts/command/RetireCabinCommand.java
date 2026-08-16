package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record RetireCabinCommand(UUID cabinId, String reason, int expectedVersion) {}
