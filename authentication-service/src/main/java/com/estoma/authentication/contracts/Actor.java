package com.estoma.authentication.contracts;

import java.util.UUID;

/**
 * The actor an envelope carries responsibility for. Authorization checks
 * must use this -- never data taken from the payload
 * (security-observability.md:156).
 */
public record Actor(String type, UUID accountId, String service, String scope, String systemId) {

  /** Keeps existing account-originated envelopes source compatible. */
  public Actor(UUID accountId) {
    this("ACCOUNT", accountId, null, null, null);
  }

  public boolean isSystemService(String expectedService) {
    return "SYSTEM".equals(type) && expectedService.equals(service);
  }
}
