package com.estoma.platformbff.contracts.http.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank String loginType,
    @NotBlank String loginIdentifier,
    @NotBlank String password,
    String deviceName) {
}
