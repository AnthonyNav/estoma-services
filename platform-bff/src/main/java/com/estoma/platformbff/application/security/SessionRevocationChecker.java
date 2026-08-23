package com.estoma.platformbff.application.security;

import java.util.UUID;

public interface SessionRevocationChecker {
  boolean isRevoked(UUID accountId, UUID personId, UUID sessionId, long issuedAtEpochSeconds);
}
