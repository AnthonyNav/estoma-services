package com.estoma.authentication.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Issues and verifies access tokens (system_contract.md "Sessions and
 * tokens": JWT RS256, 15-minute lifetime, claims {@code sub}, {@code
   * personId}, {@code sid}, {@code authState}, and {@code jti}.
 */
public interface TokenIssuer {

  IssuedAccessToken issueAccessToken(UUID accountId, UUID personId, UUID sessionId, String authState);

  /** @return the account ID the token was issued for, or empty if the token is invalid, expired, or malformed. */
  java.util.Optional<UUID> verifyAndExtractAccountId(String accessToken);

  record IssuedAccessToken(String token, Instant expiresAt) {}
}
