package com.estoma.washexecution.application.command;

public final class CommandRejectedException extends RuntimeException {
  private final String code;

  public CommandRejectedException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
