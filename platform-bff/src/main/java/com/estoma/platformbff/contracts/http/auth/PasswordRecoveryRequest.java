package com.estoma.platformbff.contracts.http.auth;

import jakarta.validation.constraints.NotBlank;

public record PasswordRecoveryRequest(
    @NotBlank String loginType,
    @NotBlank String loginIdentifier) {
}
