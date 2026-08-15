package com.estoma.authorization.application.command;

import java.util.Objects;
import java.util.UUID;

public record CausalDependency(
    String priorCommandType,
    UUID priorResultMessageId,
    String priorResultStatus,
    String expectedEventType,
    UUID expectedAggregateId,
    Integer minimumSourceVersion) {

  public CausalDependency {
    requireText(priorCommandType, "priorCommandType");
    Objects.requireNonNull(priorResultMessageId, "priorResultMessageId");
    if (!"SUCCEEDED".equals(priorResultStatus)) {
      throw new IllegalArgumentException("priorResultStatus must be SUCCEEDED");
    }
    requireText(expectedEventType, "expectedEventType");
    Objects.requireNonNull(expectedAggregateId, "expectedAggregateId");
    if (minimumSourceVersion != null && minimumSourceVersion <= 0) {
      throw new IllegalArgumentException("minimumSourceVersion must be positive");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
