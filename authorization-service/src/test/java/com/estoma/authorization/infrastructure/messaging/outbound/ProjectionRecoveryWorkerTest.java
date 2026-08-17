package com.estoma.authorization.infrastructure.messaging.outbound;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.OutboxRepository.OutboxMessage;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository.RecoveryJob;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator.ProjectionType;
import com.estoma.authorization.infrastructure.messaging.MessagingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionRecoveryWorkerTest {

  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

  @Test
  void waitsForTheCurrentQueryToPublishBeforeIssuingAnotherRecoveryQuery() {
    ProjectionRecoveryCoordinator coordinator = mock(ProjectionRecoveryCoordinator.class);
    ProjectionRecoveryJobRepository jobs = completedFullJobs();
    OutboxRepository outbox = mock(OutboxRepository.class);
    RecoveryJob waiting = waitingJob();
    OutboxMessage pending = mock(OutboxMessage.class);
    when(pending.status()).thenReturn("PENDING");
    when(jobs.findWaitingBefore(NOW.minusSeconds(30), 50)).thenReturn(List.of(waiting));
    when(outbox.findById(waiting.currentQueryMessageId())).thenReturn(Optional.of(pending));

    worker(coordinator, jobs, outbox).scheduledWork();

    verify(coordinator, never()).retry(waiting.recoveryJobId());
  }

  @Test
  void retriesOnlyAfterTheCurrentQueryWasPublished() {
    ProjectionRecoveryCoordinator coordinator = mock(ProjectionRecoveryCoordinator.class);
    ProjectionRecoveryJobRepository jobs = completedFullJobs();
    OutboxRepository outbox = mock(OutboxRepository.class);
    RecoveryJob waiting = waitingJob();
    OutboxMessage published = mock(OutboxMessage.class);
    when(published.status()).thenReturn("PUBLISHED");
    when(jobs.findWaitingBefore(NOW.minusSeconds(30), 50)).thenReturn(List.of(waiting));
    when(outbox.findById(waiting.currentQueryMessageId())).thenReturn(Optional.of(published));

    worker(coordinator, jobs, outbox).scheduledWork();

    verify(coordinator).retry(waiting.recoveryJobId());
  }

  private static ProjectionRecoveryWorker worker(
      ProjectionRecoveryCoordinator coordinator,
      ProjectionRecoveryJobRepository jobs,
      OutboxRepository outbox) {
    return new ProjectionRecoveryWorker(
        coordinator,
        jobs,
        outbox,
        new MessagingProperties(
            true,
            true,
            Duration.ofSeconds(1),
            1,
            Duration.ofMinutes(2),
            Duration.ofSeconds(1),
            false,
            Duration.ofSeconds(1),
            true,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30)),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static ProjectionRecoveryJobRepository completedFullJobs() {
    ProjectionRecoveryJobRepository jobs = mock(ProjectionRecoveryJobRepository.class);
    for (ProjectionType type : ProjectionType.values()) {
      when(jobs.hasCompletedFull(type.name())).thenReturn(true);
    }
    return jobs;
  }

  private static RecoveryJob waitingJob() {
    return new RecoveryJob(
        UUID.randomUUID(),
        "ACCOUNT_STATUS",
        "ENTITY",
        "account:" + UUID.randomUUID(),
        "WAITING_RESULT",
        null,
        1,
        "GetAccountStatusSnapshot",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        null,
        NOW.minusSeconds(31),
        NOW.minusSeconds(31),
        null,
        null,
        null);
  }
}
