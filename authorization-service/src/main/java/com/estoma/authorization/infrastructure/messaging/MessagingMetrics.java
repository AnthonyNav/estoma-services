package com.estoma.authorization.infrastructure.messaging;

import com.estoma.authorization.application.command.CommandOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class MessagingMetrics {

  private static final Set<String> ACCESS_DENIED_CODES = Set.of(
      "ACTOR_NOT_AUTHORIZED",
      "TARGET_SCOPE_NOT_ALLOWED",
      "SELF_ADMINISTRATION_FORBIDDEN",
      "SUPERADMIN_TARGET_FORBIDDEN",
      "PRODUCER_NOT_ALLOWED",
      "ACTOR_CONTEXT_INVALID");

  private final MeterRegistry registry;

  MessagingMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void commandReceived() {
    registry.counter("authorization.commands.received").increment();
    registry.counter("authorization.scope.checks").increment();
  }

  public void commandOutcome(CommandOutcome outcome) {
    outcome.result().ifPresent(result -> {
      switch (result.status()) {
        case SUCCEEDED -> registry.counter("authorization.commands.succeeded").increment();
        case REJECTED -> {
          registry.counter("authorization.commands.rejected").increment();
          if (result.error() != null && ACCESS_DENIED_CODES.contains(result.error().code())) {
            registry.counter("authorization.access.denied").increment();
          }
        }
        case FAILED -> registry.counter("authorization.commands.failed").increment();
      }
    });
  }

  public void commandFailedTerminally() {
    registry.counter("authorization.commands.failed").increment();
  }

  public void consumed(String consumer) {
    registry.counter("authorization.messages.consumed", "consumer", consumer).increment();
  }

  public void retried(String consumer) {
    registry.counter("authorization.messages.retry", "consumer", consumer).increment();
  }

  public void deadLettered(String consumer) {
    registry.counter("authorization.dlq.messages", "consumer", consumer).increment();
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

  public void processingDuration(String consumer, long nanos) {
    Timer.builder("authorization.consumer.processing.duration")
        .tag("consumer", consumer)
        .register(registry)
        .record(Duration.ofNanos(Math.max(0, nanos)));
  }
}
