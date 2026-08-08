package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/** Request for {@code ValidateRecoveryCode} (commands.md §8.4). */
public record ValidateRecoveryCodeRequest(UUID recoveryId, String code) {}
