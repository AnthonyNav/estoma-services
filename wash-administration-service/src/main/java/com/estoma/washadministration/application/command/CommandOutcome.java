package com.estoma.washadministration.application.command;

import java.util.UUID;

public record CommandOutcome(
    Status status,
    String errorCode,
    UUID aggregateId,
    Integer aggregateVersion,
    boolean duplicateDelivery) {

  public enum Status {
    SUCCEEDED,
    REJECTED,
    FAILED,
    EXPIRED,
    DUPLICATE
  }

  public static CommandOutcome duplicate() {
    return new CommandOutcome(Status.DUPLICATE, null, null, null, true);
  }

  public static CommandOutcome succeeded(UUID aggregateId, Integer aggregateVersion) {
    return new CommandOutcome(Status.SUCCEEDED, null, aggregateId, aggregateVersion, false);
  }

  public static CommandOutcome rejected(String errorCode, UUID aggregateId) {
    return new CommandOutcome(Status.REJECTED, errorCode, aggregateId, null, false);
  }

  public static CommandOutcome failed(String errorCode, UUID aggregateId, Integer aggregateVersion) {
    return new CommandOutcome(Status.FAILED, errorCode, aggregateId, aggregateVersion, false);
  }

  public static CommandOutcome expired(String errorCode, UUID aggregateId, Integer aggregateVersion) {
    return new CommandOutcome(Status.EXPIRED, errorCode, aggregateId, aggregateVersion, false);
  }
}
