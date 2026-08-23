package com.estoma.platformbff.domain.messaging;

import java.time.Instant;
import java.util.UUID;

public record InboxMessage(
    String consumerName,
    UUID messageId,
    String messageKind,
    String messageType,
    int messageVersion,
    String producer,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    UUID aggregateId,
    Integer aggregateVersion,
    Instant receivedAt,
    Instant appliedAt) {
}
