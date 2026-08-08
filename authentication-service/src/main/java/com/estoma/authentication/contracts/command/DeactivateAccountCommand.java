package com.estoma.authentication.contracts.command;

import java.util.UUID;

/** Payload of {@code DeactivateAccount} (commands.md §4.3). Also revokes the account's active sessions. */
public record DeactivateAccountCommand(UUID accountId, int expectedVersion) {}
