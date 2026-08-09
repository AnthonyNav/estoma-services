package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/** Response for a successful {@code ChangePassword} (commands.md §8.6). */
public record ChangePasswordResponse(UUID sessionId, String accessToken, String refreshToken, AuthState authState) {}
