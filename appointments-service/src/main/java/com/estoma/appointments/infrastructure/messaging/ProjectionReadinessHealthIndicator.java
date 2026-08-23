package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("projectionReadiness")
public final class ProjectionReadinessHealthIndicator implements HealthIndicator {
  private final ProjectionRecoveryCoordinator recovery;
  public ProjectionReadinessHealthIndicator(ProjectionRecoveryCoordinator recovery) { this.recovery = recovery; }
  @Override public Health health() {
    return recovery.criticalReady() ? Health.up().build() : Health.down().withDetail("reason", "critical projections are not READY").build();
  }
}
