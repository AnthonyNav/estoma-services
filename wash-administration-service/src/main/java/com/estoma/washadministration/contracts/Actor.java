package com.estoma.washadministration.contracts;

import java.util.UUID;

public record Actor(String type, UUID accountId, String service) {

  public static Actor account(UUID accountId) {
    return new Actor("ACCOUNT", accountId, null);
  }

  public static Actor system(String service) {
    return new Actor("SYSTEM", null, service);
  }
}
