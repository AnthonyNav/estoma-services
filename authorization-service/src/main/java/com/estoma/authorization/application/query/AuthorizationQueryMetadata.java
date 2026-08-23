package com.estoma.authorization.application.query;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Metadata for a side-effect-free Query whose Result must still be durably published. */
public record AuthorizationQueryMetadata(
    UUID messageId,
    String messageType,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    ActorRef actor) {

  public AuthorizationQueryMetadata {
    Objects.requireNonNull(messageId, "messageId");
    requireText(messageType, "messageType");
    requireText(producer, "producer");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(actor, "actor");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
