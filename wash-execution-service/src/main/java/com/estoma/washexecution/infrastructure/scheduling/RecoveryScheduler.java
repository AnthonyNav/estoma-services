package com.estoma.washexecution.infrastructure.scheduling;

import com.estoma.washexecution.infrastructure.messaging.recovery.ProjectionRecoveryCoordinator;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="estoma.messaging",name="recovery-worker-enabled",havingValue="true",matchIfMissing=true)
public final class RecoveryScheduler {
  private final ProjectionRecoveryCoordinator recovery;
  private final ZoneId zone;

  public RecoveryScheduler(
      ProjectionRecoveryCoordinator recovery,
      @Value("${estoma.messaging.recovery-zone:America/Mexico_City}") String zone) {
    this.recovery = recovery;
    this.zone = ZoneId.of(zone);
  }

  @Scheduled(
      fixedDelayString="${estoma.messaging.recovery-poll-interval:1000}",
      initialDelayString="${estoma.messaging.recovery-initial-delay:1000}")
  public void recover() {
    recovery.ensureBootstrap(LocalDate.now(zone));
    recovery.publishPendingQueries();
  }
}
