package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/** Request for {@code ResetPassword} (commands.md §8.5). Sole canonical name; {@code CompletePasswordRecovery} is retired. */
public record ResetPasswordRequest(UUID recoveryId, String code, String newPassword) {}
