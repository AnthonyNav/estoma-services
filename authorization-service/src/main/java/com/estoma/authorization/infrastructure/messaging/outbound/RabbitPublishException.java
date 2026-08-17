package com.estoma.authorization.infrastructure.messaging.outbound;

import java.util.UUID;

final class RabbitPublishException extends RuntimeException {

  private final PublishFailureCode code;
  private final UUID attemptId;

  RabbitPublishException(PublishFailureCode code, UUID attemptId, Throwable cause) {
    super(code.name(), cause);
    this.code = code;
    this.attemptId = attemptId;
  }

  PublishFailureCode code() {
    return code;
  }

  UUID attemptId() {
    return attemptId;
  }
}
