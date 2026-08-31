package com.estoma.platformbff.contracts.messaging;

import java.util.UUID;

public record CommandSubmission(
    String publicOperation,
    String messageType,
    String payloadJson,
    String idempotencyPayloadJson,
    String idempotencyKey,
    UUID actorAccountId,
    ActorContext actor,
    UUID aggregateId,
    Integer aggregateVersion,
    UUID idempotencyAggregateId,
    Integer idempotencyAggregateVersion) {

  /**
   * Backwards-compatible constructor for callers whose owner payload is already the exact
   * client-visible idempotency intent.
   */
  public CommandSubmission(
      String publicOperation,
      String messageType,
      String payloadJson,
      String idempotencyKey,
      UUID actorAccountId,
      ActorContext actor,
      UUID aggregateId,
      Integer aggregateVersion) {
    this(publicOperation, messageType, payloadJson, payloadJson, idempotencyKey, actorAccountId,
        actor, aggregateId, aggregateVersion, aggregateId, aggregateVersion);
  }
}
