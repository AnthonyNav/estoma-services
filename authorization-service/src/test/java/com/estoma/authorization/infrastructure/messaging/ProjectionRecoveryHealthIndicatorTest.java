package com.estoma.authorization.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.SystemCatalogRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class ProjectionRecoveryHealthIndicatorTest {

  @Test
  void trustedUpgradeWithoutFullHistoryRemainsReady() {
    ProjectionRecoveryJobRepository jobs = mock(ProjectionRecoveryJobRepository.class);
    SystemCatalogRepository systems = mock(SystemCatalogRepository.class);

    var indicator = new ProjectionRecoveryHealthIndicator(jobs, systems, properties(""));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void requiredBootstrapWithoutCompletedFullIsNotReady() {
    ProjectionRecoveryJobRepository jobs = mock(ProjectionRecoveryJobRepository.class);
    SystemCatalogRepository systems = mock(SystemCatalogRepository.class);

    var indicator = new ProjectionRecoveryHealthIndicator(
        jobs, systems, properties("ACCOUNT_STATUS"));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void failedCriticalFullIsNotReadyEvenWithoutAutomaticRetry() {
    ProjectionRecoveryJobRepository jobs = mock(ProjectionRecoveryJobRepository.class);
    SystemCatalogRepository systems = mock(SystemCatalogRepository.class);
    when(jobs.hasFailedFull("PERSON_STATUS")).thenReturn(true);

    var indicator = new ProjectionRecoveryHealthIndicator(jobs, systems, properties(""));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  private static MessagingProperties properties(String bootstrap) {
    return new MessagingProperties(
        true,
        true,
        Duration.ofSeconds(1),
        1,
        Duration.ofMinutes(2),
        Duration.ofSeconds(1),
        true,
        Duration.ofSeconds(1),
        true,
        bootstrap,
        Duration.ofSeconds(30),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30));
  }
}
