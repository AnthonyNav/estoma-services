package com.estoma.authentication.contracts.rpc;

/** Response for a successful {@code RevokeSession} (commands.md §8.3). Repeated revocation is idempotent. */
public record RevokeSessionResponse(boolean revoked) {}
