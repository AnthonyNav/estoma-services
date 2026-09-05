package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.AppointmentAvailabilityPort;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Relays cache-aside refreshes independently from client Command operations. */
@Component
@ConditionalOnProperty(prefix = "platform.outbox", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class AppointmentAvailabilityRefreshRelay {
  private static final Logger log = LoggerFactory.getLogger(AppointmentAvailabilityRefreshRelay.class);
  private final AppointmentAvailabilityPort availability;
  private final AppointmentAvailabilityQueryPublisher publisher;
  private final Clock clock;
  private final int maxAttempts;

  public AppointmentAvailabilityRefreshRelay(AppointmentAvailabilityPort availability,
      AppointmentAvailabilityQueryPublisher publisher, Clock clock,
      @Value("${platform.outbox.max-attempts:10}") int maxAttempts) {
    if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
    this.availability = availability;
    this.publisher = publisher;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  @Scheduled(fixedDelayString = "${platform.outbox.poll-interval:250ms}")
  public void relay() {
    var refresh = availability.claimNext(clock.instant()).orElse(null);
    if (refresh == null) return;
    try {
      publisher.publish(refresh);
      if (!availability.markPublished(refresh.messageId(), refresh.claimToken(), clock.instant())) {
        log.warn("lost availability refresh claim messageId={}", refresh.messageId());
      }
    } catch (RuntimeException failure) {
      boolean exhausted = refresh.attemptCount() >= maxAttempts;
      Instant next = exhausted ? null : clock.instant().plusSeconds(backoff(refresh.attemptCount()));
      availability.markFailed(refresh.messageId(), refresh.claimToken(),
          exhausted ? "PUBLISH_RETRY_EXHAUSTED" : "PUBLISH_FAILED", next);
      log.warn("availability refresh publication failed messageId={} category=technical",
          refresh.messageId());
    }
  }

  private static long backoff(int attempts) {
    return Math.min(300L, 1L << Math.min(8, Math.max(0, attempts - 1)));
  }
}
