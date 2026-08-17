package com.estoma.authorization.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public final class MessagingMetrics {

  private final MeterRegistry registry;

  MessagingMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void consumed(String consumer) {
    registry.counter("authorization.messages.consumed", "consumer", consumer).increment();
  }

  public void retried(String consumer) {
    registry.counter("authorization.messages.retry", "consumer", consumer).increment();
  }

  public void deadLettered(String consumer) {
    registry.counter("authorization.messages.dlq", "consumer", consumer).increment();
  }

  public void duplicate(String consumer) {
    registry.counter("authorization.duplicate.messages", "consumer", consumer).increment();
  }

  public void deferred() {
    registry.counter("authorization.projection.deferred").increment();
  }

  public void staleRecoveryResult() {
    registry.counter("authorization.projection.recovery.stale_result").increment();
  }
}
