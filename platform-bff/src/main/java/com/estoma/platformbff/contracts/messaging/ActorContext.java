package com.estoma.platformbff.contracts.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActorContext(
    String type,
    UUID accountId,
    String service,
    String scope,
    String systemId) {

  public static ActorContext anonymous() {
    return new ActorContext("ANONYMOUS", null, null, "PUBLIC", null);
  }

  public static ActorContext account(UUID accountId, String scope, String systemId) {
    return new ActorContext("ACCOUNT", Objects.requireNonNull(accountId, "accountId"), null,
        Objects.requireNonNull(scope, "scope"), systemId);
  }
}
