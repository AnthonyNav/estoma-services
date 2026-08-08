package com.estoma.authentication.contracts.rpc;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for a successful {@code AuthenticateAccount} (commands.md
 * §8.1). Tokens only ever travel here, via Direct Reply-To -- never in
 * events, {@code platform.results}, Outbox, Inbox, logs, or traces.
 */
public record AuthenticateAccountResponse(
    UUID accountId, UUID sessionId, String accessToken, String refreshToken, Instant expiresAt, boolean mustChangePassword) {}
