package com.estoma.registros.application;

/** A functional rejection — maps to a RESULT with status REJECTED, never to a dead-lettered message. */
public class RegistroRejectedException extends RuntimeException {
  private final String errorCode;

  public RegistroRejectedException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
