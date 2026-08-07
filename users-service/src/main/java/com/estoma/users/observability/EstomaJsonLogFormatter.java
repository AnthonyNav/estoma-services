package com.estoma.users.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Instant;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/**
 * The mandatory structured log shape (security-observability.md:446-469,
 * docs/system-contract.md §11): a fixed 9-field snake_case schema, none
 * of which match any of Boot's built-in formats (ECS/GELF/Logstash all
 * use their own field names) — so this is written by hand rather than
 * trying to remap a built-in format's keys blind. This formatter *is*
 * the log contract.
 *
 * <p>Wired via {@code logging.structured.format.console} in
 * application.yml (a fully-qualified class name), which is how Spring
 * Boot instantiates a custom formatter — via reflection, trying a small
 * set of known constructor parameter types. This class accepts {@link
 * Environment} to read {@code spring.application.name} and the
 * deployment environment name once, rather than on every log line.
 *
 * <p>Never logs passwords, hashes, tokens, or secrets — this formatter
 * only ever reads the log message, the level, the timestamp, and the
 * MDC keys {@link LogContext} itself defines, none of which carry
 * sensitive data by construction (docs/system-contract.md §12).
 */
public class EstomaJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

  private final JsonWriter<ILoggingEvent> jsonWriter;

  public EstomaJsonLogFormatter(Environment environment) {
    String service = environment.getProperty("spring.application.name", "users-service");
    String env = environment.getProperty("estoma.environment", "local");

    this.jsonWriter =
        JsonWriter.<ILoggingEvent>of(
                members -> {
                  members.add("timestamp", (ILoggingEvent e) -> Instant.ofEpochMilli(e.getTimeStamp()).toString());
                  members.add("level", (ILoggingEvent e) -> e.getLevel().toString());
                  members.add("service", service);
                  members.add("environment", env);
                  members.add("message", ILoggingEvent::getFormattedMessage);
                  members.add(LogContext.CORRELATION_ID, (ILoggingEvent e) -> mdc(e, LogContext.CORRELATION_ID))
                      .whenNotNull();
                  members.add(LogContext.MESSAGE_ID, (ILoggingEvent e) -> mdc(e, LogContext.MESSAGE_ID)).whenNotNull();
                  members.add(LogContext.ACCOUNT_ID, (ILoggingEvent e) -> mdc(e, LogContext.ACCOUNT_ID)).whenNotNull();
                  members.add(LogContext.ERROR_CODE, (ILoggingEvent e) -> mdc(e, LogContext.ERROR_CODE)).whenNotNull();
                  members.add(LogContext.CAUSATION_ID, (ILoggingEvent e) -> mdc(e, LogContext.CAUSATION_ID))
                      .whenNotNull();
                  members.add(LogContext.OPERATION_ID, (ILoggingEvent e) -> mdc(e, LogContext.OPERATION_ID))
                      .whenNotNull();
                  members.add(LogContext.AGGREGATE_ID, (ILoggingEvent e) -> mdc(e, LogContext.AGGREGATE_ID))
                      .whenNotNull();
                  members.add(LogContext.CONSUMER, (ILoggingEvent e) -> mdc(e, LogContext.CONSUMER)).whenNotNull();
                  members.add(LogContext.ATTEMPT, (ILoggingEvent e) -> mdc(e, LogContext.ATTEMPT)).whenNotNull();
                })
            .withNewLineAtEnd();
  }

  @Override
  public String format(ILoggingEvent event) {
    return jsonWriter.writeToString(event);
  }

  private static String mdc(ILoggingEvent event, String key) {
    return event.getMDCPropertyMap().get(key);
  }
}
