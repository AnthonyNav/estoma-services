package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface OutboxRepository {

  void append(OutboxMessage message);

  /**
   * Durable, transport-neutral message intent.
   *
   * <p>{@code logicalDestination} is not a RabbitMQ routing key. PR3 maps message kind, type and
   * logical destination to the canonical exchange/routing contract.
   */
  record OutboxMessage(
      UUID messageId,
      String messageType,
      int messageVersion,
      String messageKind,
      String producer,
      String logicalDestination,
      Instant occurredAt,
      UUID operationId,
      String idempotencyKey,
      UUID aggregateId,
      Integer aggregateVersion,
      UUID correlationId,
      UUID causationId,
      String actorJson,
      String payloadJson,
      String metadataJson) {

    public OutboxMessage {
      Objects.requireNonNull(actorJson, "actorJson");
      if (actorJson.isBlank()) {
        throw new IllegalArgumentException("actorJson must not be blank");
      }
      Objects.requireNonNull(logicalDestination, "logicalDestination");
      if (logicalDestination.isBlank()) {
        throw new IllegalArgumentException("logicalDestination must not be blank");
      }
    }
  }
}
