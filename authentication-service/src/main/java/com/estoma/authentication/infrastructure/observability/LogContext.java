package com.estoma.authentication.infrastructure.observability;

/**
 * The mandatory snake_case log field names fixed in system_contract.md
 * "Observability Parameters". {@link EstomaJsonLogFormatter} reads these
 * keys from SLF4J's MDC; nothing populates the MDC yet in this tramo (no
 * command/RPC handlers exist), so a log line only ever carries the base
 * fields (timestamp, level, service, environment, message) until a later
 * tramo adds a producer for each key.
 */
public final class LogContext {

  public static final String MESSAGE_ID = "message_id";
  public static final String CORRELATION_ID = "correlation_id";
  public static final String ACCOUNT_ID = "account_id";
  public static final String SESSION_ID = "session_id";
  public static final String OPERATION = "operation";
  public static final String RESULT = "result";
  public static final String ERROR_CODE = "error_code";
  public static final String DURATION = "duration";

  private LogContext() {}
}
