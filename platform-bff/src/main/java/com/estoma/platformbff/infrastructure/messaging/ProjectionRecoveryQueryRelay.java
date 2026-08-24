package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.ProjectionRecoveryQueryRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "platform.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProjectionRecoveryQueryRelay {
  private static final Logger log = LoggerFactory.getLogger(ProjectionRecoveryQueryRelay.class);
  private final ProjectionRecoveryQueryRepository repository;
  private final RecoveryQueryPublisher publisher;
  private final int maxAttempts;
  private final String workerId = UUID.randomUUID().toString();

  public ProjectionRecoveryQueryRelay(ProjectionRecoveryQueryRepository repository,
      RecoveryQueryPublisher publisher, @Value("${platform.outbox.max-attempts:10}") int maxAttempts) {
    this.repository = repository;
    this.publisher = publisher;
    this.maxAttempts = maxAttempts;
  }

  @Scheduled(fixedDelayString = "${platform.outbox.poll-interval:250ms}")
  public void relay() {
    var query = repository.claimNext(workerId, Instant.now()).orElse(null);
    if (query == null) return;
    try {
      publisher.publish(query);
      repository.markPublished(query.messageId(), query.claimToken(), Instant.now());
    } catch (RuntimeException failure) {
      boolean exhausted = query.attemptCount() >= maxAttempts;
      repository.markFailed(query.messageId(), query.claimToken(),
          exhausted ? "PUBLISH_RETRY_EXHAUSTED" : "PUBLISH_FAILED",
          exhausted ? null : Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, Math.max(0, query.attemptCount() - 1)))));
      log.warn("recovery Query publication failed messageId={} category=technical", query.messageId());
    }
  }
}
