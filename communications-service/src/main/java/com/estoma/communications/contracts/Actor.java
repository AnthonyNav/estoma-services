package com.estoma.communications.contracts;

import java.util.UUID;

/**
 * The actor an envelope carries responsibility for. Authorization checks
 * must use this -- never data taken from the payload. Exactly one of
 * {@code accountId} ({@code type == ACCOUNT}) or {@code service} ({@code
 * type == SYSTEM}) is populated; never propagates email, name, phone, or
 * any token (messaging-architecture.md §8.1).
 */
public record Actor(ActorType type, UUID accountId, String service) {

  public static Actor account(UUID accountId) {
    return new Actor(ActorType.ACCOUNT, accountId, null);
  }

  public static Actor system(String service) {
    return new Actor(ActorType.SYSTEM, null, service);
  }
}
