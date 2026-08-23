package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.time.Instant;
import java.util.UUID;

public record IncomingResult(
    UUID messageId,
    String messageType,
    int messageVersion,
    MessageKind messageKind,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID operationId,
    UUID aggregateId,
    Integer aggregateVersion,
    String status,
    String dataJson,
    String errorCode,
    String errorDetailsJson) {
}
