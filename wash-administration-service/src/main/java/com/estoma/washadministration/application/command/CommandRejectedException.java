package com.estoma.washadministration.application.command;

import java.util.UUID;

/** Functional rejection that must be persisted as a terminal Result, not retried. */
public final class CommandRejectedException extends RuntimeException {

  private final String code;
  private final UUID aggregateId;

  public CommandRejectedException(String code) {
    this(code, null, null);
  }

  public CommandRejectedException(String code, Throwable cause) {
    this(code, null, cause);
  }

  public CommandRejectedException(String code, UUID aggregateId) {
    this(code, aggregateId, null);
  }

  private CommandRejectedException(String code, UUID aggregateId, Throwable cause) {
    super(code, cause);
    this.code = code;
    this.aggregateId = aggregateId;
  }

  public String code() {
    return code;
  }

  public UUID aggregateId() {
    return aggregateId;
  }
}
