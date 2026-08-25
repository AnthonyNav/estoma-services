package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.time.Instant;
import java.util.Map;
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
    String errorDetailsJson,
    Map<String, Object> data) {

  public IncomingResult(
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
    this(messageId, messageType, messageVersion, messageKind, producer, occurredAt, correlationId,
        causationId, operationId, aggregateId, aggregateVersion, status, dataJson, errorCode,
        errorDetailsJson, null);
  }
}
