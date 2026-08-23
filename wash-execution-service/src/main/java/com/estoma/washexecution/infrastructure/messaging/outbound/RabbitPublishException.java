package com.estoma.washexecution.infrastructure.messaging.outbound;

public final class RabbitPublishException extends RuntimeException {
  public RabbitPublishException(String code, Throwable cause) { super(code, cause); }
  public RabbitPublishException(String code) { super(code); }
}
