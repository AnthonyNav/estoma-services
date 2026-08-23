package com.estoma.washexecution.domain.shared;

public class DomainRuleViolationException extends RuntimeException {

  private final String code;

  public DomainRuleViolationException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
