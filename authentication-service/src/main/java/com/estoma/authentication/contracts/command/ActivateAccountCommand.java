package com.estoma.authentication.contracts.command;

import java.util.UUID;

/** Payload of {@code ActivateAccount} (commands.md §4.2). Requires the credential to already be configured. */
public record ActivateAccountCommand(UUID accountId, int expectedVersion) {}
