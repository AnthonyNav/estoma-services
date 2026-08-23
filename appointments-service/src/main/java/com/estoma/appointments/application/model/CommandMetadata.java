package com.estoma.appointments.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Transport-neutral command metadata. RabbitMQ maps to this type in PR3. */
public record CommandMetadata(UUID messageId, String idempotencyKey, UUID correlationId,
    UUID causationId, ActorContext actor, Instant receivedAt) {
  public CommandMetadata {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is blank");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(receivedAt, "receivedAt");
  }

  public static CommandMetadata direct(ActorContext actor, Instant now) {
    UUID messageId = UUID.randomUUID();
    return new CommandMetadata(messageId, messageId.toString(), messageId, null, actor, now);
  }
}
