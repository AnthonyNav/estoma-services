package com.estoma.authorization.domain.model;

import java.util.Objects;
import java.util.UUID;

public record ActorRef(ActorType type, UUID accountId, String service) {

  public ActorRef {
    Objects.requireNonNull(type, "type");
    if (type == ActorType.ACCOUNT && (accountId == null || service != null)) {
      throw new IllegalArgumentException("ACCOUNT actor requires accountId and no service");
    }
    if (type == ActorType.SYSTEM && (accountId != null || service == null || service.isBlank())) {
      throw new IllegalArgumentException("SYSTEM actor requires service and no accountId");
    }
  }

  public static ActorRef account(UUID accountId) {
    return new ActorRef(ActorType.ACCOUNT, accountId, null);
  }

  public static ActorRef system(String service) {
    return new ActorRef(ActorType.SYSTEM, null, service);
  }
}
