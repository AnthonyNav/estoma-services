package com.estoma.users.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The mandatory consumer metrics from messaging-architecture.md §17 /
 * docs/system-contract.md §11: received, processed (by outcome),
 * duplicates detected, retries, technical failures, invalid messages,
 * and processing latency — all tagged with the Consumer Inbox name so
 * they read the same way the Inbox rows themselves are keyed.
 */
@Component
public class ConsumerMetrics {

  private final Counter received;
  private final Counter duplicates;
  private final Counter succeeded;
  private final Counter rejected;
  private final Counter invalidMessages;
  private final Counter technicalFailures;
  private final Counter retried;
  private final Counter deadLettered;
  private final Timer processingTime;

  public ConsumerMetrics(MeterRegistry registry) {
    this.received = counter(registry, "received", null);
    this.duplicates = counter(registry, "duplicates", null);
    this.succeeded = counter(registry, "processed", "succeeded");
    this.rejected = counter(registry, "processed", "rejected");
    this.invalidMessages = counter(registry, "invalid_messages", null);
    this.technicalFailures = counter(registry, "technical_failures", null);
    this.retried = counter(registry, "retries", null);
    this.deadLettered = counter(registry, "dead_lettered", null);
    this.processingTime =
        Timer.builder("estoma.consumer.processing_time")
            .tag("consumer", ConsumerNames.COMMANDS)
            .register(registry);
  }

  private static Counter counter(MeterRegistry registry, String name, String outcome) {
    Counter.Builder builder =
        Counter.builder("estoma.consumer." + name).tag("consumer", ConsumerNames.COMMANDS);
    if (outcome != null) {
      builder = builder.tag("outcome", outcome);
    }
    return builder.register(registry);
  }

  public void received() {
    received.increment();
  }

  public void duplicate() {
    duplicates.increment();
  }

  public void succeeded() {
    succeeded.increment();
  }

  public void rejected() {
    rejected.increment();
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
