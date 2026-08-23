package com.estoma.washexecution.application.command;

import java.util.Map;
import java.util.UUID;

public record CommandOutcome(
    Status status,
    String errorCode,
    UUID aggregateId,
    Integer aggregateVersion,
    Map<String, Object> data) {

  public enum Status { SUCCEEDED, REJECTED, DUPLICATE }

  public static CommandOutcome succeeded(UUID aggregateId, int aggregateVersion, Map<String, Object> data) {
    return new CommandOutcome(Status.SUCCEEDED, null, aggregateId, aggregateVersion, data);
  }

  public static CommandOutcome rejected(String errorCode, UUID aggregateId) {
    return new CommandOutcome(Status.REJECTED, errorCode, aggregateId, null, Map.of());
  }

  public static CommandOutcome duplicate(String errorCode, UUID aggregateId, Integer aggregateVersion) {
    return new CommandOutcome(Status.DUPLICATE, errorCode, aggregateId, aggregateVersion, Map.of());
  }
}
