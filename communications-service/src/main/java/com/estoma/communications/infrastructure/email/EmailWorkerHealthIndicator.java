package com.estoma.communications.infrastructure.email;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group (execution_plan.md
 * Phase 2 step 12): DOWN if the most recent {@link
 * EmailDeliveryWorker#pollAndProcess()} run threw. A poll that has not run
 * yet (fresh startup) is not DOWN -- there is nothing to report failure
 * about.
 *
 * <p>Bean name {@code emailWorkerHealthIndicator} is what makes Spring
 * register this contributor under the id {@code emailWorker} referenced in
 * {@code application.yml}'s health groups.
 */
@Component
class EmailWorkerHealthIndicator implements HealthIndicator {

  private final EmailDeliveryWorker worker;

  EmailWorkerHealthIndicator(EmailDeliveryWorker worker) {
    this.worker = worker;
  }

  @Override
  public Health health() {
    String lastError = worker.lastPollError();
    if (lastError != null) {
      return Health.down().withDetail("lastError", lastError).build();
    }
    return Health.up().build();
  }
}
