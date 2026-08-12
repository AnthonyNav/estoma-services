package com.estoma.academic.observability;

import com.estoma.academic.persistence.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Operational counters and gauges for Academic's durable Outbox. */
@Component
public class AcademicOutboxMetrics {
  private final OutboxRepository outbox;
  private final Clock clock;
  private final AtomicLong pending = new AtomicLong();
  private final AtomicLong stuck = new AtomicLong();
  private final AtomicLong oldestAge = new AtomicLong();
  private final Counter published;
  private final Counter failed;
  private final Counter claimLost;
  private final Counter retried;

  public AcademicOutboxMetrics(OutboxRepository outbox, MeterRegistry registry) {
    this.outbox = outbox;
    this.clock = Clock.systemUTC();
    Gauge.builder("academic.outbox.pending", pending, AtomicLong::get).register(registry);
    Gauge.builder("academic.outbox.stuck_processing", stuck, AtomicLong::get).register(registry);
    Gauge.builder("academic.outbox.oldest_pending_age_seconds", oldestAge, AtomicLong::get).register(registry);
    published = Counter.builder("academic.outbox.published_total").register(registry);
    failed = Counter.builder("academic.outbox.failed_total").register(registry);
    claimLost = Counter.builder("academic.outbox.claim_lost_total").register(registry);
    retried = Counter.builder("academic.outbox.retry_total").register(registry);
  }

  @Scheduled(fixedDelay = 10_000)
  public void refresh() {
    pending.set(outbox.pendingCount());
    stuck.set(outbox.stuckProcessingCount());
    Instant oldest = outbox.oldestPendingAvailableAt();
    oldestAge.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds()));
  }

  public void published() { published.increment(); }
  public void failed() { failed.increment(); }
  public void claimLost() { claimLost.increment(); }
  public void retried() { retried.increment(); }
}
