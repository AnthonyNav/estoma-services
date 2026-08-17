package com.estoma.authorization.application.exception;

/**
 * Signals that a compare-and-set mutation lost its optimistic-lock race.
 *
 * <p>This exception intentionally does not extend {@link AuthorizationRuleViolationException}:
 * it must escape the business transaction so every mutation performed earlier in that command is
 * rolled back. The canonical command boundary converts it to a durable functional rejection only
 * after rollback.
 */
public final class OptimisticLockConflictException extends RuntimeException {

  private final AuthorizationErrorCode code;

  public OptimisticLockConflictException(AuthorizationErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public AuthorizationErrorCode code() {
    return code;
  }
}
