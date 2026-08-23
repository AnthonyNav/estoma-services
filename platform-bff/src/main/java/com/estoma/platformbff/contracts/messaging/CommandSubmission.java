package com.estoma.platformbff.contracts.messaging;

import java.util.UUID;
public record CommandSubmission(
    String publicOperation,
    String messageType,
    String payloadJson,
    String idempotencyKey,
    UUID actorAccountId,
    ActorContext actor,
    UUID aggregateId,
    Integer aggregateVersion) {
}
