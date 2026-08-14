package com.estoma.authorization.domain.model;

import java.util.Objects;

public record PermissionCode(String value) {

  public PermissionCode {
    Objects.requireNonNull(value, "value");
    if (value.isBlank() || value.length() > 160) {
      throw new IllegalArgumentException("permission code must be non-blank and at most 160 characters");
    }
  }
}
