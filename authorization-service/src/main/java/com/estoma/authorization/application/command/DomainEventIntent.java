package com.estoma.authorization.application.command;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DomainEventIntent(
    UUID messageId,
    String messageType,
    int messageVersion,
    String producer,
    String routingKey,
    UUID operationId,
    String idempotencyKey,
    ActorRef actor,
    UUID aggregateId,
    int aggregateVersion,
    UUID correlationId,
    UUID causationId,
    String payloadJson,
    Instant occurredAt) {

  public DomainEventIntent {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(messageType, "messageType");
    Objects.requireNonNull(producer, "producer");
    Objects.requireNonNull(routingKey, "routingKey");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(causationId, "causationId");
    Objects.requireNonNull(payloadJson, "payloadJson");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (messageVersion <= 0 || aggregateVersion <= 0) {
      throw new IllegalArgumentException("message and aggregate versions must be positive");
    }
  }
}
