package com.estoma.appointments.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Normalized Execution fact used by PR2 handlers and populated by PR3 wire adapters. */
public record ExecutionEvent(UUID messageId, UUID sourceEventId, UUID washExecutionId,
    UUID appointmentId, String eventType, long executionVersion, Instant occurredAt,
    Instant arrivedAt, Instant actionAt, UUID actorAccountId, Instant startedAt,
    String cancellationType, String cancellationSubreason, String reason, String payloadJson) {
  public ExecutionEvent {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(washExecutionId, "washExecutionId");
    Objects.requireNonNull(appointmentId, "appointmentId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (executionVersion <= 0) throw new IllegalArgumentException("executionVersion must be positive");
  }

  public Instant effectiveArrivalAt() { return arrivedAt == null ? occurredAt : arrivedAt; }
  public Instant effectiveActionAt() { return actionAt == null ? occurredAt : actionAt; }
  public String payloadOrEmpty() { return payloadJson == null ? "{}" : payloadJson; }
}
