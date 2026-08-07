package com.estoma.users.observability;

import com.estoma.users.persistence.JdbcOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The mandatory Outbox metrics (security-observability.md:547-554,
 * docs/system-contract.md §11): pending count, age of the oldest overdue
 * pending message, successful/failed publishes, retries, claims lost to
 * fencing, and messages stuck in PROCESSING.
 *
 * <p>The three gauges are backed by an {@link AtomicLong} refreshed by a
 * scheduled query every 10 seconds, never by the scrape itself — a
 * Prometheus scrape must never trigger a query against {@code
 * outbox_messages} (docs/system-contract.md §8, §11).
 *
 * <p>{@code stuck_processing} counts only PROCESSING rows whose lease has
 * already expired, never every PROCESSING row — one mid-flight in a live
 * relay batch is normal. The age gauge measures how overdue the oldest
 * reclaimable PENDING row is (against {@code available_at}), not how old
 * it is since creation ({@code occurred_at}) — a row that just finished a
 * long, legitimate backoff must not look identical to a stalled relay.
 */
@Component
public class OutboxMetrics {

  private final JdbcOutboxRepository outboxRepository;
  private final Clock clock;

  private final AtomicLong pendingCount = new AtomicLong();
  private final AtomicLong stuckInProcessingCount = new AtomicLong();
  private final AtomicLong overduePendingAgeSeconds = new AtomicLong();

  private final Counter publishedCounter;
  private final Counter failedPublishCounter;
  private final Counter claimLostCounter;

  public OutboxMetrics(JdbcOutboxRepository outboxRepository, MeterRegistry registry, Clock clock) {
    this.outboxRepository = outboxRepository;
    this.clock = clock;

    Gauge.builder("estoma.outbox.pending", pendingCount, AtomicLong::get).register(registry);
    Gauge.builder("estoma.outbox.stuck_processing", stuckInProcessingCount, AtomicLong::get).register(registry);
    Gauge.builder("estoma.outbox.oldest_pending_age_seconds", overduePendingAgeSeconds, AtomicLong::get)
        .register(registry);

    this.publishedCounter = Counter.builder("estoma.outbox.published").register(registry);
    this.failedPublishCounter = Counter.builder("estoma.outbox.failed_publish").register(registry);
    this.claimLostCounter = Counter.builder("estoma.outbox.claim_lost").register(registry);
  }

  @Scheduled(fixedDelay = 10_000)
  public void refresh() {
    pendingCount.set(outboxRepository.countPending());
    stuckInProcessingCount.set(outboxRepository.countStuckProcessing());

    Instant oldestOverduePending = outboxRepository.oldestOverduePendingAvailableAt();
    overduePendingAgeSeconds.set(
        oldestOverduePending == null ? 0 : Duration.between(oldestOverduePending, clock.instant()).toSeconds());
  }

  public void published() {
    publishedCounter.increment();
  }

  public void failedPublish() {
    failedPublishCounter.increment();
  }

  /**
   * A fenced transition affected zero rows — the worker's claim was
   * already reclaimed by another worker or the sweeper. Never bundled
   * with {@link #failedPublish()}: RabbitMQ may well have confirmed the
   * publication, the failure was purely local claim bookkeeping
   * (docs/system-contract.md §8, §10).
   */
  public void claimLost() {
    claimLostCounter.increment();
  }
}
