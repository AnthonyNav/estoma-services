package com.estoma.platformbff.contracts.http.auth;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
    @NotBlank String loginType,
    @NotBlank String loginIdentifier,
    @NotBlank String code,
    @NotBlank String newPassword) {
}
