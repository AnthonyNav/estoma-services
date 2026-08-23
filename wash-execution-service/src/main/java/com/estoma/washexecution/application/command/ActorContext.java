package com.estoma.washexecution.application.command;

import java.util.UUID;

public record ActorContext(String type, UUID accountId, String service) {
  public static ActorContext account(UUID accountId) {
    return new ActorContext("ACCOUNT", accountId, null);
  }

  public static ActorContext system(String service) {
    return new ActorContext("SYSTEM", null, service);
  }

  public boolean isAccount() {
    return "ACCOUNT".equals(type) && accountId != null;
  }
}
