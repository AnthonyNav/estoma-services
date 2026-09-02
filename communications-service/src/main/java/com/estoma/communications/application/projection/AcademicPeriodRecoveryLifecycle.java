package com.estoma.communications.application.projection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Invokes recovery through the transactional coordinator proxy at startup and while it is overdue. */
@Component
class AcademicPeriodRecoveryLifecycle {

  private final AcademicPeriodRecoveryCoordinator coordinator;
  private final boolean enabled;

  AcademicPeriodRecoveryLifecycle(
      AcademicPeriodRecoveryCoordinator coordinator,
      @Value("${estoma.communications.academic-period.recovery-enabled:true}") boolean enabled) {
    this.coordinator = coordinator;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void requestBootstrapAtStartup() {
    if (enabled) {
      coordinator.requestBootstrap();
    }
  }

  @Scheduled(fixedDelayString = "${estoma.communications.academic-period.recovery-retry-delay:60000}")
  public void retryOverdueBootstrap() {
    if (enabled) {
      coordinator.retryIfOverdue();
    }
  }
}
