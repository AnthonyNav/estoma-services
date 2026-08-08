package com.estoma.authentication.contracts.command;

import com.estoma.authentication.contracts.LoginType;

/**
 * Payload of {@code RequestPasswordRecovery} (commands.md §6.1). Durable
 * because it carries no password, code, or token supplied by the user --
 * only the login identifier whose recovery is being requested.
 */
public record RequestPasswordRecoveryCommand(LoginType loginType, String loginIdentifier) {}
