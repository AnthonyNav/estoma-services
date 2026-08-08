package com.estoma.authentication.contracts.rpc;

import java.time.Instant;

/** Response for a successful {@code RefreshSession} (commands.md §8.2). The refresh token is rotated on every call. */
public record RefreshSessionResponse(String accessToken, String refreshToken, Instant expiresAt) {}
