package com.estoma.washadministration.domain.shared;

public final class DomainRuleViolationException extends IllegalArgumentException {

  private final DomainErrorCode code;

  public DomainRuleViolationException(DomainErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public DomainErrorCode code() {
    return code;
  }
}
