package com.estoma.washadministration.application.command;

import java.util.UUID;

public record CommandOutcome(
    String status,
    String errorCode,
    UUID aggregateId,
    Integer aggregateVersion,
    boolean duplicateDelivery) {

  public static CommandOutcome duplicate() {
    return new CommandOutcome("DUPLICATE", null, null, null, true);
  }

  public static CommandOutcome succeeded(UUID aggregateId, Integer aggregateVersion) {
    return new CommandOutcome("SUCCEEDED", null, aggregateId, aggregateVersion, false);
  }

  public static CommandOutcome rejected(String errorCode, UUID aggregateId) {
    return new CommandOutcome("REJECTED", errorCode, aggregateId, null, false);
  }
}
