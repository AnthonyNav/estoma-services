package com.estoma.authentication.contracts.rpc;

import java.time.Instant;

/** Response for a successful {@code ChangePassword} (commands.md §8.6). */
public record ChangePasswordResponse(String accessToken, String refreshToken, Instant expiresAt) {}
