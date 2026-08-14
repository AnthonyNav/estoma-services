package com.estoma.authorization.domain.exception;

public final class DomainRuleViolationException extends RuntimeException {

  private final DomainErrorCode code;

  public DomainRuleViolationException(DomainErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public DomainErrorCode code() {
    return code;
  }
}
