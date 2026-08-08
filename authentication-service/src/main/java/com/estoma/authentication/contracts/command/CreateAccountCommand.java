package com.estoma.authentication.contracts.command;

import com.estoma.authentication.contracts.LoginType;
import java.util.UUID;

/** Payload of {@code CreateAccount} (commands.md §4.1). Never carries a password or any secret. */
public record CreateAccountCommand(UUID accountId, UUID personId, LoginType loginType, String loginIdentifier) {}
