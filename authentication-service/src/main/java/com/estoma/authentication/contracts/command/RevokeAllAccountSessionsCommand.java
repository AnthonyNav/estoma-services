package com.estoma.authentication.contracts.command;

import java.util.UUID;

/**
 * Payload of {@code RevokeAllAccountSessions} (commands.md §5.1). Distinct
 * from the ephemeral RPC {@code RevokeSession}: this is an administrative
 * operation with no user secret involved, so it travels as a durable
 * command.
 */
public record RevokeAllAccountSessionsCommand(UUID accountId, String reason) {}
