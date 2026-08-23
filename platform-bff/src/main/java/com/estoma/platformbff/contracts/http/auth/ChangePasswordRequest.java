package com.estoma.platformbff.contracts.http.auth;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(String currentPassword, @NotBlank String newPassword) {
}
