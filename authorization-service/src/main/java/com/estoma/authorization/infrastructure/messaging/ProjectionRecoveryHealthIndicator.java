package com.estoma.authorization.infrastructure.messaging;

import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator.ProjectionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("projectionRecovery")
@ConditionalOnProperty(
    prefix = "estoma.messaging", name = "recovery-worker-enabled", havingValue = "true", matchIfMissing = true)
final class ProjectionRecoveryHealthIndicator implements HealthIndicator {

  private final ProjectionRecoveryJobRepository jobs;

  ProjectionRecoveryHealthIndicator(ProjectionRecoveryJobRepository jobs) {
    this.jobs = jobs;
  }

  @Override
  public Health health() {
    try {
      for (ProjectionType projectionType : ProjectionType.values()) {
        String type = projectionType.name();
        if (jobs.hasFailedFull(type)) {
          return Health.down().withDetail("reason", type + " FULL recovery failed").build();
        }
        if (jobs.hasActiveFull(type)) {
          return Health.down().withDetail("reason", type + " FULL recovery is active").build();
        }
        if (!jobs.hasCompletedFull(type)) {
          return Health.down().withDetail("reason", type + " FULL recovery has not completed").build();
        }
      }
      return Health.up().build();
    } catch (RuntimeException exception) {
      return Health.down(exception).build();
    }
  }
}
