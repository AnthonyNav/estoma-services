package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record CancelAdministrativeClosureCommand(UUID closureId, String reason, int expectedVersion) {}
