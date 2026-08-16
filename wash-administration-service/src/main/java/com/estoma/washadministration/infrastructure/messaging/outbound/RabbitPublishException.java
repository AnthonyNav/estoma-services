package com.estoma.washadministration.infrastructure.messaging.outbound;

import java.util.UUID;

public class RabbitPublishException extends RuntimeException {

  private final OutboxFailureCode code;
  private final UUID publishAttemptId;

  public RabbitPublishException(OutboxFailureCode code, UUID publishAttemptId, Throwable cause) {
    super(code.name(), cause);
    this.code = code;
    this.publishAttemptId = publishAttemptId;
  }

  public OutboxFailureCode code() {
    return code;
  }

  public UUID publishAttemptId() {
    return publishAttemptId;
  }
}
