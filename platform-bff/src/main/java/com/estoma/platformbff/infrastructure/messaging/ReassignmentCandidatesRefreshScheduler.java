package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded fair sweep that refreshes pending executions independently of HTTP reads. */
@Component
@ConditionalOnProperty(prefix = "platform.wash.reassignment-candidates", name = "enabled",
    havingValue = "true")
public class ReassignmentCandidatesRefreshScheduler {
  private final ReassignmentCandidatesPort candidates;
  private final Clock clock;
  private final Duration refreshInterval;
  private final Duration retention;
  private final int batchSize;

  public ReassignmentCandidatesRefreshScheduler(ReassignmentCandidatesPort candidates, Clock clock,
      @Value("${platform.wash.reassignment-candidates.refresh-interval:5s}") Duration refreshInterval,
      @Value("${platform.wash.reassignment-candidates.refresh-retention:1h}") Duration retention,
      @Value("${platform.wash.reassignment-candidates.batch-size:25}") int batchSize) {
    if (refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()
        || refreshInterval.compareTo(Duration.ofSeconds(10)) >= 0) {
      throw new IllegalArgumentException("reassignment refresh interval must be within (0, 10s)");
    }
    if (retention == null || retention.isNegative() || retention.isZero() || batchSize < 1
        || batchSize > 1000) {
      throw new IllegalArgumentException("invalid reassignment refresh sweep configuration");
    }
    this.candidates = candidates;
    this.clock = clock;
    this.refreshInterval = refreshInterval;
    this.retention = retention;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${platform.wash.reassignment-candidates.sweep-interval:1s}")
  public void sweep() {
    var now = clock.instant();
    candidates.enqueueDuePending(now, now.minus(refreshInterval), now.minusSeconds(30), batchSize);
    candidates.cleanupInactive(now.minus(retention), batchSize);
  }
}
