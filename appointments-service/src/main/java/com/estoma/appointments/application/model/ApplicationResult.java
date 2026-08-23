package com.estoma.appointments.application.model;

import java.util.Objects;
import java.util.UUID;

/** Durable result returned by every application boundary. */
public record ApplicationResult(Status status, String code, String message, UUID aggregateId,
    long aggregateVersion, String payloadJson) {
  public enum Status { APPLIED, REJECTED, DEFERRED, DUPLICATE }

  public ApplicationResult {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
    if (aggregateVersion < 0) throw new IllegalArgumentException("aggregateVersion cannot be negative");
  }

  public static ApplicationResult applied(UUID id, long version, String message) {
    return new ApplicationResult(Status.APPLIED, "APPLIED", message, id, version, null);
  }

  public static ApplicationResult rejected(String code, String message) {
    return new ApplicationResult(Status.REJECTED, code, message, null, 0, null);
  }

  public static ApplicationResult deferred(String code, String message, UUID id) {
    return new ApplicationResult(Status.DEFERRED, code, message, id, 0, null);
  }

  public static ApplicationResult duplicate() {
    return new ApplicationResult(Status.DUPLICATE, "DUPLICATE", "message already applied", null, 0, null);
  }

  public boolean terminal() {
    return status == Status.APPLIED || status == Status.REJECTED || status == Status.DUPLICATE;
  }
}
