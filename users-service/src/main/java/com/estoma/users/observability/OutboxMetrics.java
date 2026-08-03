package com.estoma.users.observability;

import com.estoma.users.persistence.JdbcOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The mandatory Outbox metrics (security-observability.md:547-554,
 * docs/system-contract.md §11): pending count, age of the oldest
 * pending message, successful/failed publishes, retries, and messages
 * stuck in PROCESSING.
 *
 * <p>The three gauges are backed by an {@link AtomicLong} refreshed by a
 * scheduled query every 10 seconds, never by the scrape itself — a
 * Prometheus scrape must never trigger a query against {@code
 * outbox_messages} (docs/system-contract.md §8, §11).
 */
@Component
public class OutboxMetrics {

  private final JdbcOutboxRepository outboxRepository;

  private final AtomicLong pendingCount = new AtomicLong();
  private final AtomicLong stuckInProcessingCount = new AtomicLong();
  private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

  private final Counter publishedCounter;
  private final Counter failedPublishCounter;

  public OutboxMetrics(JdbcOutboxRepository outboxRepository, MeterRegistry registry) {
    this.outboxRepository = outboxRepository;

    Gauge.builder("estoma.outbox.pending", pendingCount, AtomicLong::get).register(registry);
    Gauge.builder("estoma.outbox.stuck_processing", stuckInProcessingCount, AtomicLong::get).register(registry);
    Gauge.builder("estoma.outbox.oldest_pending_age_seconds", oldestPendingAgeSeconds, AtomicLong::get)
        .register(registry);

    this.publishedCounter = Counter.builder("estoma.outbox.published").register(registry);
    this.failedPublishCounter = Counter.builder("estoma.outbox.failed_publish").register(registry);
  }

  @Scheduled(fixedDelay = 10_000)
  public void refresh() {
    pendingCount.set(outboxRepository.countPending());
    stuckInProcessingCount.set(outboxRepository.countProcessing());

    Instant oldestPending = outboxRepository.oldestPendingOccurredAt();
    oldestPendingAgeSeconds.set(
        oldestPending == null ? 0 : Duration.between(oldestPending, Instant.now()).toSeconds());
  }

  public void published() {
    publishedCounter.increment();
  }

  public void failedPublish() {
    failedPublishCounter.increment();
  }
}
