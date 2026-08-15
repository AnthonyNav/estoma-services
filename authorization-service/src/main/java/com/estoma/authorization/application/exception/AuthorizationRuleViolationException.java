package com.estoma.authorization.application.exception;

public final class AuthorizationRuleViolationException extends RuntimeException {

  private final AuthorizationErrorCode code;

  public AuthorizationRuleViolationException(AuthorizationErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public AuthorizationErrorCode code() {
    return code;
  }
}
