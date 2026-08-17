package com.estoma.washadministration.contracts.command;

import java.util.UUID;

public record RegisterCabinCommand(UUID cabinId, String code, String name, String cabinType) {}
