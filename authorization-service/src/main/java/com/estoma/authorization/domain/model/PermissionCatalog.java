package com.estoma.authorization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PermissionCatalog(
    UUID id,
    UUID systemId,
    PermissionCode code,
    String name,
    CatalogStatus status,
    Instant createdAt,
    Instant updatedAt) {

  public PermissionCatalog {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(systemId, "systemId");
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
