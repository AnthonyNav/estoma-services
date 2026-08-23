package com.estoma.platformbff.contracts.http.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RefreshRequest(@NotNull UUID sessionId, @NotBlank String refreshToken) {
}
