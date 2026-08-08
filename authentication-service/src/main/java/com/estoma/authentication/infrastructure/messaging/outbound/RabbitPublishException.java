package com.estoma.authentication.infrastructure.messaging.outbound;

import java.util.UUID;

/**
 * Thrown by {@link ConfirmedRabbitPublisher} whenever a publication
 * cannot be confirmed as acknowledged and routed. Carries the sanitized
 * {@link OutboxFailureCode} callers log, and the {@code publishAttemptId}
 * used to correlate this specific attempt against RabbitMQ -- distinct
 * from the wire {@code messageId}, which never changes across
 * republications.
 *
 * <p>The exception message is a fixed, sanitized string built only from
 * {@code code}. The original {@code cause} is preserved for diagnostic
 * logging but never itself persisted.
 */
public final class RabbitPublishException extends RuntimeException {

  private final OutboxFailureCode code;
  private final UUID publishAttemptId;

  public RabbitPublishException(OutboxFailureCode code, UUID publishAttemptId, Throwable cause) {
    super("RabbitMQ publication failed with code " + code, cause);
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
