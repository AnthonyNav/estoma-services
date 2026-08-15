package com.estoma.authorization.application.command;

import java.util.UUID;
import java.util.Objects;

public record AuthoritativeEvidence(
    String sourceService,
    String sourceOperation,
    String sourceResultStatus,
    UUID sourceResultMessageId,
    String entityType,
    UUID entityId,
    Integer evidenceVersion) {

  public AuthoritativeEvidence {
    requireText(sourceService, "sourceService");
    requireText(sourceOperation, "sourceOperation");
    requireText(sourceResultStatus, "sourceResultStatus");
    requireText(entityType, "entityType");
    Objects.requireNonNull(entityId, "entityId");
    if (!sourceResultStatus.equals("SUCCEEDED") && !sourceResultStatus.equals("FOUND")) {
      throw new IllegalArgumentException("sourceResultStatus must be SUCCEEDED or FOUND");
    }
    if (evidenceVersion != null && evidenceVersion <= 0) {
      throw new IllegalArgumentException("evidenceVersion must be positive");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}
