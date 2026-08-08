package com.estoma.users.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * {@code queries.users-service}'s own metrics, parallel to {@link
 * ConsumerMetrics} rather than a generalization of it: {@link
 * ConsumerMetrics} fixes its Micrometer tag to {@link
 * ConsumerNames#COMMANDS} at construction as a singleton {@code
 * @Component}, so sharing it across two consumers would mean
 * {@code @Qualifier}-annotating {@code CommandListener}'s constructor —
 * touching stable, tested production code — just to support a second
 * consumer. A small parallel class avoids that.
 *
 * <p>No {@code duplicate()}/{@code rejected()} counters: a Query has no
 * Consumer Inbox (nothing to report as a duplicate) and no functional
 * rejection catalog (queries.md §2 — every well-formed request produces
 * a successful resolution, even {@code NOT_FOUND}); {@link #resolved()}
 * replaces {@code succeeded()} to name that accurately.
 */
@Component
public class QueryConsumerMetrics {

  private final Counter received;
  private final Counter resolved;
  private final Counter invalidMessages;
  private final Counter technicalFailures;
  private final Counter retried;
  private final Counter deadLettered;
  private final Timer processingTime;

  public QueryConsumerMetrics(MeterRegistry registry) {
    this.received = counter(registry, "received", null);
    this.resolved = counter(registry, "processed", "resolved");
    this.invalidMessages = counter(registry, "invalid_messages", null);
    this.technicalFailures = counter(registry, "technical_failures", null);
    this.retried = counter(registry, "retries", null);
    this.deadLettered = counter(registry, "dead_lettered", null);
    this.processingTime =
        Timer.builder("estoma.consumer.processing_time")
            .tag("consumer", ConsumerNames.QUERIES)
            .register(registry);
  }

  private static Counter counter(MeterRegistry registry, String name, String outcome) {
    Counter.Builder builder =
        Counter.builder("estoma.consumer." + name).tag("consumer", ConsumerNames.QUERIES);
    if (outcome != null) {
      builder = builder.tag("outcome", outcome);
    }
    return builder.register(registry);
  }

  public void received() {
    received.increment();
  }

  public void resolved() {
    resolved.increment();
  }

  public void invalidMessage() {
    invalidMessages.increment();
  }

  public void technicalFailure() {
    technicalFailures.increment();
  }

  public void retried() {
    retried.increment();
  }

  public void deadLettered() {
    deadLettered.increment();
  }

  public void recordProcessingTime(Duration duration) {
    processingTime.record(duration);
  }
}
