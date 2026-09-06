package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable retry relay for reassignment-candidate refresh Queries. */
@Component
@ConditionalOnProperty(prefix = "platform.wash.reassignment-candidates", name = "enabled",
    havingValue = "true")
public class ReassignmentCandidatesRefreshRelay {
  private static final Logger log = LoggerFactory.getLogger(ReassignmentCandidatesRefreshRelay.class);
  private final ReassignmentCandidatesPort candidates;
  private final ReassignmentCandidatesQueryPublisher publisher;
  private final Clock clock;
  private final int maxAttempts;

  public ReassignmentCandidatesRefreshRelay(ReassignmentCandidatesPort candidates,
      ReassignmentCandidatesQueryPublisher publisher, Clock clock,
      @Value("${platform.outbox.max-attempts:10}") int maxAttempts) {
    if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
    this.candidates = candidates;
    this.publisher = publisher;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  @Scheduled(fixedDelayString = "${platform.outbox.poll-interval:250ms}")
  public void relay() {
    var refresh = candidates.claimNext(clock.instant()).orElse(null);
    if (refresh == null) return;
    try {
      publisher.publish(refresh);
      if (!candidates.markPublished(refresh.messageId(), refresh.claimToken(), clock.instant())) {
        log.warn("lost reassignment refresh claim messageId={}", refresh.messageId());
      }
    } catch (RuntimeException failure) {
      boolean exhausted = refresh.attemptCount() >= maxAttempts;
      Instant next = exhausted ? null : clock.instant().plusSeconds(backoff(refresh.attemptCount()));
      candidates.markFailed(refresh.messageId(), refresh.claimToken(),
          exhausted ? "PUBLISH_RETRY_EXHAUSTED" : "PUBLISH_FAILED", next);
      log.warn("reassignment refresh publication failed messageId={} category=technical",
          refresh.messageId());
    }
  }

  private static long backoff(int attempts) {
    return Math.min(300L, 1L << Math.min(8, Math.max(0, attempts - 1)));
  }
}
