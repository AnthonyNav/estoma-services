package com.estoma.authorization.application.projection;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectionEventMetadata(
    UUID messageId,
    String messageType,
    int messageVersion,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String idempotencyKey,
    ActorRef actor,
    UUID aggregateId,
    int aggregateVersion) {

  public ProjectionEventMetadata {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(messageType, "messageType");
    Objects.requireNonNull(producer, "producer");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(aggregateId, "aggregateId");
    if (messageVersion <= 0 || aggregateVersion <= 0) {
      throw new IllegalArgumentException("message and aggregate versions must be positive");
    }
  }
}
