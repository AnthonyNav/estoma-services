package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record RetireTankCommand(UUID tankId, String reason, int expectedVersion) {}
