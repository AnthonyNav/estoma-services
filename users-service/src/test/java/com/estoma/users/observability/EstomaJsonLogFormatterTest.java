package com.estoma.users.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies the mandatory 9-field snake_case log shape
 * (security-observability.md:446-469, docs/system-contract.md §11)
 * directly, without booting a Spring context: this formatter is a pure
 * function of an {@code ILoggingEvent} once constructed.
 */
class EstomaJsonLogFormatterTest {

  private final MockEnvironment environment =
      new MockEnvironment().withProperty("spring.application.name", "users-service").withProperty("estoma.environment", "test");

  private final EstomaJsonLogFormatter formatter = new EstomaJsonLogFormatter(environment);

  @Test
  void includesTheMandatoryFieldsEvenWithNoMdcContext() {
    String json = formatter.format(event(Level.INFO, "hello", Map.of()));

    assertThat(json).contains("\"timestamp\":");
    assertThat(json).contains("\"level\":\"INFO\"");
    assertThat(json).contains("\"service\":\"users-service\"");
    assertThat(json).contains("\"environment\":\"test\"");
    assertThat(json).contains("\"message\":\"hello\"");
  }

  @Test
  void omitsConditionalFieldsWhenAbsentFromMdc() {
    String json = formatter.format(event(Level.INFO, "no context here", Map.of()));

    assertThat(json).doesNotContain("correlation_id");
    assertThat(json).doesNotContain("message_id");
    assertThat(json).doesNotContain("account_id");
    assertThat(json).doesNotContain("error_code");
    assertThat(json).doesNotContain("causation_id");
    assertThat(json).doesNotContain("operation_id");
    assertThat(json).doesNotContain("aggregate_id");
    assertThat(json).doesNotContain("consumer");
    assertThat(json).doesNotContain("attempt");
  }

  @Test
  void includesEveryMdcFieldWhenAllArePresent() {
    Map<String, String> mdc =
        Map.of(
            LogContext.CORRELATION_ID, "corr-1",
            LogContext.MESSAGE_ID, "msg-1",
            LogContext.ACCOUNT_ID, "acct-1",
            LogContext.ERROR_CODE, "PERSON_NOT_FOUND",
            LogContext.CAUSATION_ID, "cause-1",
            LogContext.OPERATION_ID, "op-1",
            LogContext.AGGREGATE_ID, "agg-1",
            LogContext.CONSUMER, "users-service.commands");

    String json = formatter.format(event(Level.WARN, "with context", mdc));

    assertThat(json).contains("\"correlation_id\":\"corr-1\"");
    assertThat(json).contains("\"message_id\":\"msg-1\"");
    assertThat(json).contains("\"account_id\":\"acct-1\"");
    assertThat(json).contains("\"error_code\":\"PERSON_NOT_FOUND\"");
    assertThat(json).contains("\"causation_id\":\"cause-1\"");
    assertThat(json).contains("\"operation_id\":\"op-1\"");
    assertThat(json).contains("\"aggregate_id\":\"agg-1\"");
    assertThat(json).contains("\"consumer\":\"users-service.commands\"");
  }

  @Test
  void neverLogsSecretLikeKeysBecauseTheFormatterOnlyReadsItsOwnDeclaredFields() {
    // Even if a caller carelessly put a secret-sounding key in the MDC,
    // the formatter only ever reads the fixed set of keys LogContext
    // declares -- there is no "log everything in MDC" fallback.
    String json =
        formatter.format(
            event(Level.INFO, "message", Map.of("password", "should-never-appear", "token", "also-hidden")));

    assertThat(json).doesNotContain("should-never-appear");
    assertThat(json).doesNotContain("also-hidden");
    assertThat(json).doesNotContain("\"password\"");
    assertThat(json).doesNotContain("\"token\"");
  }

  private static LoggingEvent event(Level level, String message, Map<String, String> mdc) {
    Logger logger = (Logger) LoggerFactory.getLogger(EstomaJsonLogFormatterTest.class);
    LoggingEvent event = new LoggingEvent(EstomaJsonLogFormatterTest.class.getName(), logger, level, message, null, null);
    event.setMDCPropertyMap(mdc);
    return event;
  }
}
