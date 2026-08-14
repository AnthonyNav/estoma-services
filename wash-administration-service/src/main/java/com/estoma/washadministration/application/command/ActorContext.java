package com.estoma.washadministration.application.command;

import java.util.Objects;
import java.util.UUID;

/** Internal actor representation. Wire envelopes are intentionally not part of PR2. */
public record ActorContext(String type, UUID accountId, String service) {

  public static ActorContext account(UUID accountId) {
    return new ActorContext("ACCOUNT", Objects.requireNonNull(accountId, "accountId"), null);
  }

  public static ActorContext system(String service) {
    return new ActorContext("SYSTEM", null, Objects.requireNonNull(service, "service"));
  }
}
