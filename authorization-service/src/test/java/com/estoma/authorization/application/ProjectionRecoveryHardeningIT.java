package com.estoma.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator.ProjectionType;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResultHandler;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProjectionRecoveryHardeningIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");

  @Autowired private ProjectionRecoveryCoordinator recovery;
  @Autowired private ProjectionRecoveryResultHandler results;
  @Autowired private ProjectionRecoveryJobRepository jobs;

  @Test
  void failedFullRetryCreatesNewGenerationAndPreservesFailedJob() {
    var first = recovery.requestFull(ProjectionType.ACCOUNT_STATUS);
    var failedResult = new ProjectionRecoveryResult(
        metadata(first, "authentication", UUID.randomUUID()),
        ProjectionRecoveryResult.Status.FAILED,
        List.of(),
        null,
        false,
        "TECHNICAL_FAILURE");

    assertThat(results.handle(failedResult).status())
        .isEqualTo(ProjectionRecoveryResultHandler.Status.FAILED);
    assertThat(jobs.findById(first.recoveryJobId())).get()
        .extracting(ProjectionRecoveryJobRepository.RecoveryJob::status)
        .isEqualTo("FAILED");

    var second = recovery.requestNewFullRecovery(ProjectionType.ACCOUNT_STATUS);

    assertThat(second.recoveryJobId()).isNotEqualTo(first.recoveryJobId());
    assertThat(second.currentQueryMessageId()).isNotEqualTo(first.currentQueryMessageId());
    assertThat(second.currentOperationId()).isNotEqualTo(first.currentOperationId());
    assertThat(second.currentCorrelationId()).isNotEqualTo(first.currentCorrelationId());
    assertThat(second.cursor()).isNull();
    assertThat(second.status()).isEqualTo("WAITING_RESULT");
    assertThat(jobs.findById(first.recoveryJobId())).get()
        .extracting(ProjectionRecoveryJobRepository.RecoveryJob::status)
        .isEqualTo("FAILED");
  }

  @Test
  void concurrentResultsAdvanceRecoveryExactlyOnceAndSecondIsStale() throws Exception {
    var job = recovery.requestAccount(UUID.randomUUID());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    Callable<ProjectionRecoveryResultHandler.Status> delivery = () -> {
      ready.countDown();
      start.await();
      return results.handle(new ProjectionRecoveryResult(
              metadata(job, "authentication", UUID.randomUUID()),
              ProjectionRecoveryResult.Status.NOT_FOUND,
              List.of(),
              null,
              false,
              null))
          .status();
    };

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(delivery);
      var second = executor.submit(delivery);
      ready.await();
      start.countDown();
      var statuses = List.of(first.get(), second.get());

      assertThat(statuses).containsExactlyInAnyOrder(
          ProjectionRecoveryResultHandler.Status.NOT_FOUND_CONFIRMED,
          ProjectionRecoveryResultHandler.Status.STALE_RESULT);
    }

    assertThat(jobs.findById(job.recoveryJobId())).get()
        .extracting(ProjectionRecoveryJobRepository.RecoveryJob::status)
        .isEqualTo("COMPLETED");
  }

  private static ProjectionRecoveryResult.Metadata metadata(
      ProjectionRecoveryJobRepository.RecoveryJob job,
      String producer,
      UUID messageId) {
    return new ProjectionRecoveryResult.Metadata(
        messageId,
        job.currentQueryType() + "Result",
        producer,
        NOW,
        job.currentCorrelationId(),
        job.currentQueryMessageId(),
        job.currentOperationId(),
        ActorRef.system(producer));
  }
}
