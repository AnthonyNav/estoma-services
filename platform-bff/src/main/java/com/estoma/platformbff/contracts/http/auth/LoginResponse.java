package com.estoma.platformbff.contracts.http.auth;

import java.util.UUID;

public record LoginResponse(
    UUID accountId,
    UUID sessionId,
    String accessToken,
    String refreshToken,
    String authState) {
}
