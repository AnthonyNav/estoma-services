package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record RegisterTankCommand(
    UUID tankId,
    UUID cabinId,
    String code,
    String name,
    int configuredCapacity) {}
