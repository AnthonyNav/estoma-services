package com.estoma.jornadas.application;

import java.util.Map;

/** A functional rejection — maps to a RESULT with status REJECTED, never to a dead-lettered message. */
public class JornadaRejectedException extends RuntimeException {
  private final String errorCode;
  private final Map<String, Object> details;

  public JornadaRejectedException(String errorCode, String message) {
    this(errorCode, message, Map.of());
  }

  public JornadaRejectedException(String errorCode, String message, Map<String, Object> details) {
    super(message);
    this.errorCode = errorCode;
    this.details = details;
  }

  public String errorCode() {
    return errorCode;
  }

  public Map<String, Object> details() {
    return details;
  }
}
