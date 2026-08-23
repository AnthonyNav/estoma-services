package com.estoma.platformbff.contracts.http.auth;

import java.time.Instant;

public record RefreshResponse(String accessToken, String refreshToken, Instant expiresAt) {
}
