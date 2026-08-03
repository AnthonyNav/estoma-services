package com.estoma.users.application.command;

import java.util.UUID;

/** The as-received {@code ActivatePerson} payload (commands.md §4). */
public record ActivatePersonCommand(UUID personId, int expectedVersion) implements PersonCommand {}
