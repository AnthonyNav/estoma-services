package com.estoma.authentication.contracts.rpc;

/** Response for a successful {@code ChangePassword} (commands.md §8.6). */
public record ChangePasswordResponse(boolean changed) {}
