package com.estoma.washexecution.application.command;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class WashExecutionEvents {
  private WashExecutionEvents() {}

  public record Event(
      String type,
      UUID aggregateId,
      int aggregateVersion,
      Instant occurredAt,
      Map<String, Object> payload) {}
}
