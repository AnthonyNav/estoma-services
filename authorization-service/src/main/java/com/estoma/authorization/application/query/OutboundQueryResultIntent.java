package com.estoma.authorization.application.query;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable Result obligation for a Query received by Authorization. */
public record OutboundQueryResultIntent(
    UUID messageId,
    String messageType,
    String producer,
    String routingKey,
    UUID operationId,
    ActorRef actor,
    UUID correlationId,
    UUID causationId,
    String payloadJson,
    Instant occurredAt) {

  public OutboundQueryResultIntent {
    Objects.requireNonNull(messageId, "messageId");
    requireText(messageType, "messageType");
    requireText(producer, "producer");
    requireText(routingKey, "routingKey");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(causationId, "causationId");
    Objects.requireNonNull(payloadJson, "payloadJson");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
