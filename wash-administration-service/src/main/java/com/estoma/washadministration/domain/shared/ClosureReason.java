package com.estoma.washadministration.domain.shared;

public record ClosureReason(String value) {

  public ClosureReason {
    if (value == null || value.isBlank()) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_REASON, "closure reason must not be blank");
    }
    value = value.trim();
    if (value.length() > 500) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_REASON, "closure reason must not exceed 500 characters");
    }
  }
}
