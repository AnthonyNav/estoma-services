package com.estoma.users.application.command;

import java.util.UUID;

/** The as-received {@code DeactivatePerson} payload (commands.md §5). */
public record DeactivatePersonCommand(UUID personId, int expectedVersion) implements PersonCommand {}
