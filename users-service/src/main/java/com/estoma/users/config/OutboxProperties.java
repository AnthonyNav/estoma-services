package com.estoma.users.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables for the Transactional Outbox relay and its housekeeping sweep.
 * See docs/system-contract.md §8, §10 (D8), §11.
 *
 * <p>{@code claimBatchSize} and {@code lease} are cross-validated against
 * {@code MessagingProperties.publisherConfirmTimeout} at startup by {@code
 * OutboxLeaseBudgetValidator}: the relay publishes one row at a time and
 * waits for its confirm, so a batch's worst case takes up to
 * {@code claimBatchSize × publisherConfirmTimeout}; the lease must outlast
 * that plus a safety margin, or the sweeper reclaims rows the relay is
 * still working on.
 */
@ConfigurationProperties("estoma.outbox")
public record OutboxProperties(
    /** How many PENDING rows the relay claims per poll. */
    @DefaultValue("10") int claimBatchSize,
    /** How long a claimed (PROCESSING) row's lease lasts before the sweeper reclaims it. */
    @DefaultValue("2m") Duration lease,
    /** Retry attempts after which a still-PENDING row is marked FAILED. */
    @DefaultValue("10") int maxAttempts,
    /** How long a PUBLISHED row is kept before the sweeper purges it. */
    @DefaultValue("7d") Duration publishedRetention,
    /** How long a Consumer Inbox record is kept before the sweeper purges it (D8). */
    @DefaultValue("30d") Duration inboxRetention,
    /**
     * The backoff ladder {@code ConfiguredOutboxBackoffPolicy} indexes by
     * attempt number; the last value is reused for every attempt beyond
     * the list's length. No jitter in this round.
     */
    @DefaultValue({"5s", "30s", "2m", "5m", "10m", "15m"}) List<Duration> retryDelays) {

  public OutboxProperties {
    if (retryDelays == null || retryDelays.isEmpty()) {
      throw new IllegalArgumentException("estoma.outbox.retry-delays must not be empty");
    }
    for (Duration delay : retryDelays) {
      if (delay == null || delay.isNegative() || delay.isZero()) {
        throw new IllegalArgumentException("estoma.outbox.retry-delays must all be positive, got " + retryDelays);
      }
    }
  }
}
