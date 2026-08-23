package com.estoma.platformbff.contracts.messaging;

import java.util.Map;
import java.util.Objects;

public record OwnerRoute(
    String logicalComponentId,
    String routingKey,
    int commandMessageVersion,
    Map<String, Integer> resultMessageVersions,
    ActorMode actorMode) {

  public enum ActorMode { ACCOUNT, ANONYMOUS }

  public OwnerRoute {
    Objects.requireNonNull(logicalComponentId, "logicalComponentId");
    Objects.requireNonNull(routingKey, "routingKey");
    Objects.requireNonNull(actorMode, "actorMode");
    resultMessageVersions = Map.copyOf(resultMessageVersions);
  }

  public boolean acceptsResult(String messageType, int version) {
    return Objects.equals(resultMessageVersions.get(messageType), version);
  }
}
