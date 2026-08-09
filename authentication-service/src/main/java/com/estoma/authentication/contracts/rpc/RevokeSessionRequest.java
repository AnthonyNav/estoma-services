package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/** Request for {@code RevokeSession} (commands.md §8.3), on behalf of the authenticated actor's own session. */
public record RevokeSessionRequest(UUID sessionId) {}
