package com.estoma.authentication.contracts.command;

import java.util.UUID;

/** Payload of {@code ChangeLoginIdentifier} (commands.md §4.4). */
public record ChangeLoginIdentifierCommand(UUID accountId, int expectedVersion, String newLoginIdentifier) {}
