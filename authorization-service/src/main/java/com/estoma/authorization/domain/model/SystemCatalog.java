package com.estoma.authorization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SystemCatalog(
    UUID id,
    SystemCode code,
    String name,
    CatalogStatus status,
    int version,
    Instant createdAt,
    Instant updatedAt) {

  public SystemCatalog {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(status, "status");
    requireText(name, "name", 160);
    if (version <= 0) {
      throw new IllegalArgumentException("version must be positive");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  private static void requireText(String value, String field, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be non-blank and at most " + maxLength + " characters");
    }
  }
}
