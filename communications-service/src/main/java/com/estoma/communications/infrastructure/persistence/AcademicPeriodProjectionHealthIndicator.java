package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.AcademicPeriodProjectionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness is DOWN while an enabled Academic bootstrap cannot yet supply a trusted retention date. */
@Component
class AcademicPeriodProjectionHealthIndicator implements HealthIndicator {

  private final AcademicPeriodProjectionRepository repository;
  private final boolean recoveryEnabled;

  AcademicPeriodProjectionHealthIndicator(
      AcademicPeriodProjectionRepository repository,
      @Value("${estoma.communications.academic-period.recovery-enabled:true}") boolean recoveryEnabled) {
    this.repository = repository;
    this.recoveryEnabled = recoveryEnabled;
  }

  @Override
  public Health health() {
    if (!recoveryEnabled) return Health.up().withDetail("disabled", true).build();
    String readiness = repository.readiness();
    return "READY".equals(readiness)
        ? Health.up().withDetail("readiness", readiness).build()
        : Health.down().withDetail("readiness", readiness).build();
  }
}
