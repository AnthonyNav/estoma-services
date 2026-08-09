package com.estoma.authentication.contracts.rpc;

import com.estoma.authentication.contracts.LoginType;

/** Request for {@code ValidateRecoveryCode} (commands.md §8.4). */
public record ValidateRecoveryCodeRequest(LoginType loginType, String loginIdentifier, String code) {}
