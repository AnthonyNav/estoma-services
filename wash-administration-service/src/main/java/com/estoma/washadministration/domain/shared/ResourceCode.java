package com.estoma.washadministration.domain.shared;

public record ResourceCode(String value) {

  public ResourceCode {
    if (value == null || value.isBlank()) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_CODE, "code must not be blank");
    }
    value = value.trim();
    if (value.length() > 80) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_CODE, "code must not exceed 80 characters");
    }
  }
}
