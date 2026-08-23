package com.estoma.appointments.application.model;

import java.util.Objects;
import java.util.UUID;

/** Transport-neutral ActorContext matching the common messaging envelope. */
public record ActorContext(String type, UUID accountId, String service) {
  public ActorContext {
    Objects.requireNonNull(type, "type");
    if (!type.equals("ACCOUNT") && !type.equals("SYSTEM")) {
      throw new IllegalArgumentException("actor type must be ACCOUNT or SYSTEM");
    }
    if (type.equals("ACCOUNT") && accountId == null) {
      throw new IllegalArgumentException("ACCOUNT actor requires accountId");
    }
    if (type.equals("SYSTEM") && (service == null || service.isBlank())) {
      throw new IllegalArgumentException("SYSTEM actor requires service");
    }
  }

  public static ActorContext account(UUID accountId) {
    return new ActorContext("ACCOUNT", Objects.requireNonNull(accountId, "accountId"), null);
  }

  public static ActorContext system(String service) {
    return new ActorContext("SYSTEM", null, Objects.requireNonNull(service, "service"));
  }

  public boolean isAccount() { return "ACCOUNT".equals(type); }
  public boolean isSystem() { return "SYSTEM".equals(type); }
}
