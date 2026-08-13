package com.estoma.washadministration.domain.shared;

public record DisplayName(String value) {

  public DisplayName {
    if (value == null || value.isBlank()) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_NAME, "name must not be blank");
    }
    value = value.trim();
    if (value.length() > 160) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_NAME, "name must not exceed 160 characters");
    }
  }
}
