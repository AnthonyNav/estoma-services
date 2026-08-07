package com.estoma.users.observability;

import com.estoma.users.contract.MessageEnvelope;
import org.slf4j.MDC;

/**
 * Populates SLF4J's MDC with the mandatory snake_case correlation fields
 * (security-observability.md:446-469, docs/system-contract.md §11), so
 * {@link EstomaJsonLogFormatter} can pick them up for every log line
 * emitted while a message is being processed.
 *
 * <p>Keys are snake_case (log/audit convention) even though the wire
 * envelope itself is camelCase — that split is intentional and documented
 * (docs/system-contract.md §2), not an inconsistency.
 */
public final class LogContext {

  public static final String CORRELATION_ID = "correlation_id";
  public static final String MESSAGE_ID = "message_id";
  public static final String ACCOUNT_ID = "account_id";
  public static final String ERROR_CODE = "error_code";
  public static final String CAUSATION_ID = "causation_id";
  public static final String OPERATION_ID = "operation_id";
  public static final String AGGREGATE_ID = "aggregate_id";
  public static final String CONSUMER = "consumer";
  public static final String ATTEMPT = "attempt";

  private LogContext() {}

  /** Called once per delivery, before any processing, from {@code CommandListener}. */
  public static void putEnvelopeContext(MessageEnvelope envelope, String consumerName) {
    put(MESSAGE_ID, envelope.messageId());
    put(CORRELATION_ID, envelope.correlationId());
    put(CAUSATION_ID, envelope.causationId());
    put(OPERATION_ID, envelope.operationId());
    put(AGGREGATE_ID, envelope.aggregateId());
    put(ACCOUNT_ID, envelope.actor() == null ? null : envelope.actor().accountId());
    MDC.put(CONSUMER, consumerName);
  }

  public static void putErrorCode(String errorCode) {
    MDC.put(ERROR_CODE, errorCode);
  }

  public static void putAttempt(int attempt) {
    MDC.put(ATTEMPT, String.valueOf(attempt));
  }

  /** Always called from a {@code finally} block once processing finishes. */
  public static void clear() {
    MDC.clear();
  }

  private static void put(String key, Object value) {
    if (value != null) {
      MDC.put(key, value.toString());
    }
  }
}
