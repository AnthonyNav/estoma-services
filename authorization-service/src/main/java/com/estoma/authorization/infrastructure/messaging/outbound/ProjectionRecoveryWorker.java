package com.estoma.authorization.infrastructure.messaging.outbound;

import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator.ProjectionType;
import com.estoma.authorization.infrastructure.messaging.MessagingProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives durable recovery jobs without keeping a database transaction open while waiting for Results. */
@Component
final class ProjectionRecoveryWorker {

  private static final int RETRY_BATCH_SIZE = 50;

  private final ProjectionRecoveryCoordinator coordinator;
  private final ProjectionRecoveryJobRepository jobs;
  private final OutboxRepository outbox;
  private final MessagingProperties properties;
  private final Clock clock;

  ProjectionRecoveryWorker(
      ProjectionRecoveryCoordinator coordinator,
      ProjectionRecoveryJobRepository jobs,
      OutboxRepository outbox,
      MessagingProperties properties,
      Clock authorizationClock) {
    this.coordinator = coordinator;
    this.jobs = jobs;
    this.outbox = outbox;
    this.properties = properties;
    this.clock = authorizationClock;
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.recovery-poll-interval:1s}")
  public void scheduledWork() {
    if (!properties.recoveryWorkerEnabled()) {
      return;
    }
    initializeRequestedFullRecovery();
    retryExpiredResults();
  }

  private void initializeRequestedFullRecovery() {
    for (ProjectionType projectionType : ProjectionType.values()) {
      String type = projectionType.name();
      if (!properties.bootstrapRequested(type)) {
        continue;
      }
      if (!jobs.hasActiveFull(type)
          && !jobs.hasCompletedFull(type)
          && !jobs.hasFailedFull(type)) {
        coordinator.requestFull(projectionType);
      }
    }
  }

  private void retryExpiredResults() {
    Instant cutoff = clock.instant().minus(properties.recoveryResultTimeout());
    for (ProjectionRecoveryJobRepository.RecoveryJob waiting :
        jobs.findWaitingBefore(cutoff, RETRY_BATCH_SIZE)) {
      if (!currentQueryWasPublished(waiting)) {
        continue;
      }
      try {
        coordinator.retry(waiting.recoveryJobId());
      } catch (IllegalStateException ignored) {
        // Another replica advanced the durable state machine first.
      }
    }
  }

  private boolean currentQueryWasPublished(ProjectionRecoveryJobRepository.RecoveryJob waiting) {
    return waiting.currentQueryMessageId() != null
        && outbox.findById(waiting.currentQueryMessageId())
            .map(message -> "PUBLISHED".equals(message.status()))
            .orElse(false);
  }
}
