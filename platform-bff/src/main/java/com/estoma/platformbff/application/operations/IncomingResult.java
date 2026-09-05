package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.ActorContext;
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
    String idempotencyKey,
    UUID aggregateId,
    Integer aggregateVersion,
    ActorContext actor,
    String status,
    String dataJson,
    String errorCode,
    String errorDetailsJson,
    Map<String, Object> data,
    Map<String, Object> payload) {

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
      String errorDetailsJson,
      Map<String, Object> data) {
    this(messageId, messageType, messageVersion, messageKind, producer, occurredAt, correlationId,
        causationId, operationId, null, aggregateId, aggregateVersion, null, status, dataJson,
        errorCode, errorDetailsJson, data, null);
  }

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
        causationId, operationId, null, aggregateId, aggregateVersion, null, status, dataJson,
        errorCode, errorDetailsJson, null, null);
  }
}
