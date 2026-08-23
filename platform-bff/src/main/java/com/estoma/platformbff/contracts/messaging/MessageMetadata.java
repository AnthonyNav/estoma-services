package com.estoma.platformbff.contracts.messaging;

import java.time.Instant;
import java.util.UUID;

public record MessageMetadata(
    UUID messageId,
    String messageType,
    int messageVersion,
    MessageKind messageKind,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    String idempotencyKey,
    UUID aggregateId,
    Integer aggregateVersion) {
}
