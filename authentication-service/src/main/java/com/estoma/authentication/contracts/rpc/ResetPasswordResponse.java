package com.estoma.authentication.contracts.rpc;

/** Response for a successful {@code ResetPassword} (commands.md §8.5). */
public record ResetPasswordResponse(boolean reset) {}
