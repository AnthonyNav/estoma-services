package com.estoma.platformbff.contracts.http.auth;

import jakarta.validation.constraints.NotBlank;

public record RecoveryCodeRequest(
    @NotBlank String loginType,
    @NotBlank String loginIdentifier,
    @NotBlank String code) {
}
