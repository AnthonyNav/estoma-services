package com.estoma.authorization.application.recovery;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Transport-neutral durable Query emitted by projection recovery. */
public record OutboundQueryIntent(
    UUID messageId,
    String messageType,
    int messageVersion,
    String producer,
    String routingKey,
    UUID operationId,
    ActorRef actor,
    UUID correlationId,
    UUID causationId,
    String payloadJson,
    Instant occurredAt) {

  public OutboundQueryIntent {
    Objects.requireNonNull(messageId, "messageId");
    requireText(messageType, "messageType");
    if (messageVersion <= 0) {
      throw new IllegalArgumentException("messageVersion must be positive");
    }
    requireText(producer, "producer");
    requireText(routingKey, "routingKey");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(payloadJson, "payloadJson");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
