package com.estoma.users.application.command;

import java.util.UUID;

/**
 * The as-received {@code DeactivatePerson} payload, before domain
 * validation (commands.md §7). {@code reasonCode} is required; {@code
 * reasonDetail} is optional except when {@code reasonCode == "OTHER"}
 * (commands.md §7.1) — both are validated by the handler, not here.
 */
public record DeactivatePersonCommand(UUID personId, int expectedVersion, String reasonCode, String reasonDetail)
    implements PersonCommand {}
