package com.estoma.authorization.application.command;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CommandMetadata(
    UUID messageId,
    String messageType,
    int messageVersion,
    String messageKind,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String idempotencyKey,
    ActorRef actor,
    UUID aggregateId,
    Integer aggregateVersion) {

  public CommandMetadata {
    Objects.requireNonNull(messageId, "messageId");
    requireText(messageType, "messageType");
    if (messageVersion <= 0) {
      throw new IllegalArgumentException("messageVersion must be positive");
    }
    if (!"COMMAND".equals(messageKind)) {
      throw new IllegalArgumentException("messageKind must be COMMAND");
    }
    requireText(producer, "producer");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(operationId, "operationId");
    requireText(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(actor, "actor");
  }

  public static CommandMetadata create(
      UUID messageId,
      String messageType,
      String producer,
      UUID correlationId,
      UUID operationId,
      String idempotencyKey,
      ActorRef actor,
      Instant occurredAt) {
    return new CommandMetadata(
        messageId,
        messageType,
        1,
        "COMMAND",
        producer,
        occurredAt,
        correlationId,
        null,
        operationId,
        idempotencyKey,
        actor,
        null,
        null);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
