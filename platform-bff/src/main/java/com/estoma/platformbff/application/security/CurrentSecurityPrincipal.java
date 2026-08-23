package com.estoma.platformbff.application.security;

import java.util.Optional;
import java.util.UUID;

public interface CurrentSecurityPrincipal {
  Optional<Principal> current();

  record Principal(UUID accountId, UUID personId, UUID sessionId, String authState) {
    public Principal {
      if (accountId == null || personId == null || sessionId == null || authState == null || authState.isBlank()) {
        throw new IllegalArgumentException("security principal is incomplete");
      }
    }
  }
}
