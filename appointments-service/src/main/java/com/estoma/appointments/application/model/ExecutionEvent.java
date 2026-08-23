package com.estoma.appointments.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Normalized Execution fact used by PR2 handlers and populated by PR3 wire adapters. */
public record ExecutionEvent(UUID messageId, UUID sourceEventId, UUID washExecutionId,
    UUID appointmentId, String eventType, long executionVersion, Instant occurredAt,
    Instant arrivedAt, Instant actionAt, UUID actorAccountId, Instant startedAt,
    String cancellationType, String cancellationSubreason, String reason, String payloadJson,
    UUID correlationId) {
  public ExecutionEvent {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(washExecutionId, "washExecutionId");
    Objects.requireNonNull(appointmentId, "appointmentId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(correlationId, "correlationId");
    if (executionVersion <= 0) throw new IllegalArgumentException("executionVersion must be positive");
  }

  /** Compatibility constructor for broker-agnostic PR2 tests/callers. */
  public ExecutionEvent(UUID messageId, UUID sourceEventId, UUID washExecutionId,
      UUID appointmentId, String eventType, long executionVersion, Instant occurredAt,
      Instant arrivedAt, Instant actionAt, UUID actorAccountId, Instant startedAt,
      String cancellationType, String cancellationSubreason, String reason, String payloadJson) {
    this(messageId, sourceEventId, washExecutionId, appointmentId, eventType, executionVersion,
        occurredAt, arrivedAt, actionAt, actorAccountId, startedAt, cancellationType,
        cancellationSubreason, reason, payloadJson, messageId);
  }

  public Instant effectiveArrivalAt() { return arrivedAt == null ? occurredAt : arrivedAt; }
  public Instant effectiveActionAt() { return actionAt == null ? occurredAt : actionAt; }
  public String payloadOrEmpty() { return payloadJson == null ? "{}" : payloadJson; }
}
