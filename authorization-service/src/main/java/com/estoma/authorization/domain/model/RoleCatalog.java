package com.estoma.authorization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RoleCatalog(
    UUID id,
    RoleCode code,
    String name,
    CatalogStatus status,
    Instant createdAt,
    Instant updatedAt) {

  public RoleCatalog {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(status, "status");
    if (name == null || name.isBlank() || name.length() > 160) {
      throw new IllegalArgumentException("name must be non-blank and at most 160 characters");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }
}
