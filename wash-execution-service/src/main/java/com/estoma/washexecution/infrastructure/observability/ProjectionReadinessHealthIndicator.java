package com.estoma.washexecution.infrastructure.observability;

import com.estoma.washexecution.application.port.ProjectionRecoveryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("projectionReadiness")
public final class ProjectionReadinessHealthIndicator implements HealthIndicator {
  private static final List<String> REQUIRED = List.of(
      "appointments", "wash-administration", "authorization");

  private final ProjectionRecoveryRepository recovery;

  public ProjectionReadinessHealthIndicator(ProjectionRecoveryRepository recovery) {
    this.recovery = recovery;
  }

  @Override
  public Health health() {
    Map<String, Object> details = new LinkedHashMap<>();
    boolean ready = true;
    for (String projection : REQUIRED) {
      String status = recovery.find(projection)
          .map(ProjectionRecoveryRepository.RecoveryState::status)
          .orElse("MISSING");
      details.put(projection, status);
      if (!"READY".equals(status)) ready = false;
    }
    return (ready ? Health.up() : Health.down())
        .withDetails(details)
        .build();
  }
}
