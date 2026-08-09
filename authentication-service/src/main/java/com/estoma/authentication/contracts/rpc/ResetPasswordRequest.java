package com.estoma.authentication.contracts.rpc;

import com.estoma.authentication.contracts.LoginType;

/** Request for {@code ResetPassword} (commands.md §8.5). Sole canonical name; {@code CompletePasswordRecovery} is retired. */
public record ResetPasswordRequest(LoginType loginType, String loginIdentifier, String code, String newPassword) {}
