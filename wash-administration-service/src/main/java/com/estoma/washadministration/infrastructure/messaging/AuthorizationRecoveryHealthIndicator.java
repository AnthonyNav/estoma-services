package com.estoma.washadministration.infrastructure.messaging;

import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("authorizationRecovery")
public class AuthorizationRecoveryHealthIndicator implements HealthIndicator {

  private final ProjectionRecoveryJobRepository jobs;

  public AuthorizationRecoveryHealthIndicator(ProjectionRecoveryJobRepository jobs) {
    this.jobs = jobs;
  }

  @Override
  public Health health() {
    try {
      if (jobs.hasActiveFullRecovery()) {
        return Health.down().withDetail("reason", "authorization projection FULL recovery is active").build();
      }
      if (jobs.hasFailedFullRecovery()) {
        return Health.down().withDetail("reason", "authorization projection FULL recovery failed").build();
      }
      return Health.up().build();
    } catch (RuntimeException exception) {
      return Health.down(exception).build();
    }
  }
}
