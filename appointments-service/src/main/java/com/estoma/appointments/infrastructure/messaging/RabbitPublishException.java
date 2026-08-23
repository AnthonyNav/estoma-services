package com.estoma.appointments.infrastructure.messaging;

public final class RabbitPublishException extends RuntimeException {
  public RabbitPublishException(String message) { super(message); }
  public RabbitPublishException(String message, Throwable cause) { super(message, cause); }
}
