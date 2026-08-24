package com.estoma.platformbff.application.projection;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Transport-neutral Event after the infrastructure adapter has crossed the wire boundary. */
public record ProjectionEvent(
    UUID messageId,
    String messageType,
    int messageVersion,
    String producer,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    UUID aggregateId,
    Integer aggregateVersion,
    Map<String, Object> payload) {
  public ProjectionEvent {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }
}
