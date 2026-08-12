package com.estoma.academic.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counters for the durable command and external-event consumers. */
@Component
public class AcademicMessagingMetrics {
  private final Counter consumed;
  private final Counter duplicates;
  private final Counter retries;
  private final Counter dlq;

  public AcademicMessagingMetrics(MeterRegistry registry) {
    consumed = Counter.builder("academic.messages.consumed_total").register(registry);
    duplicates = Counter.builder("academic.messages.duplicate_total").register(registry);
    retries = Counter.builder("academic.messages.retry_total").register(registry);
    dlq = Counter.builder("academic.messages.dlq_total").register(registry);
  }

  public void consumed() { consumed.increment(); }
  public void duplicate() { duplicates.increment(); }
  public void retry() { retries.increment(); }
  public void dlq() { dlq.increment(); }
}
