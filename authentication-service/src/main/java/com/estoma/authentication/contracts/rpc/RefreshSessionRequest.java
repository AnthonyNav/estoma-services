package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/** Request for {@code RefreshSession} (commands.md §8.2). */
public record RefreshSessionRequest(UUID sessionId, String refreshToken) {}
