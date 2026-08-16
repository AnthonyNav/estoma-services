package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record ActivateTankCommand(UUID tankId, int expectedVersion) {}
