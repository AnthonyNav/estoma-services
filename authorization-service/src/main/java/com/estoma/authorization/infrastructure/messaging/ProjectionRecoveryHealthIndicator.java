package com.estoma.authorization.infrastructure.messaging;

import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator.ProjectionType;
import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.SystemCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("projectionRecovery")
@ConditionalOnProperty(
    prefix = "estoma.messaging", name = "recovery-worker-enabled", havingValue = "true", matchIfMissing = true)
final class ProjectionRecoveryHealthIndicator implements HealthIndicator {

  private final ProjectionRecoveryJobRepository jobs;
  private final SystemCatalogRepository systems;
  private final MessagingProperties properties;

  ProjectionRecoveryHealthIndicator(
      ProjectionRecoveryJobRepository jobs,
      SystemCatalogRepository systems,
      MessagingProperties properties) {
    this.jobs = jobs;
    this.systems = systems;
    this.properties = properties;
  }

  @Override
  public Health health() {
    try {
      for (ProjectionType projectionType : ProjectionType.values()) {
        String type = projectionType.name();
        if (!isGloballyCritical(projectionType)) {
          continue;
        }
        if (jobs.hasActiveFull(type)) {
          return Health.down().withDetail("reason", type + " FULL recovery is active").build();
        }
        if (jobs.hasFailedFull(type)) {
          return Health.down().withDetail("reason", type + " required FULL recovery failed").build();
        }
        if (properties.bootstrapRequested(type) && !jobs.hasCompletedFull(type)) {
          return Health.down().withDetail("reason", type + " required FULL recovery has not completed").build();
        }
      }
      return Health.up().build();
    } catch (RuntimeException exception) {
      return Health.down(exception).build();
    }
  }

  private boolean isGloballyCritical(ProjectionType projectionType) {
    if (projectionType != ProjectionType.ACADEMIC_ELIGIBILITY) {
      return true;
    }
    return systems.findByCode(SystemCode.PRACTICAS_PROFESIONALES.name())
        .map(system -> system.status() == CatalogStatus.ACTIVE)
        .orElse(false);
  }
}
