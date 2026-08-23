package com.estoma.appointments.infrastructure.messaging;

public final class InvalidMessageException extends RuntimeException {
  public InvalidMessageException(String message) { super(message); }
  public InvalidMessageException(String message, Throwable cause) { super(message, cause); }
}
