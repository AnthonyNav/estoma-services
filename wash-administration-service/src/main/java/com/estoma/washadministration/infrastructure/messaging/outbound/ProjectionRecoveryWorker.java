package com.estoma.washadministration.infrastructure.messaging.outbound;

import com.estoma.washadministration.infrastructure.messaging.MessagingProperties;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProjectionRecoveryWorker {

  private final AuthorizationProjectionRecoveryCoordinator coordinator;
  private final MessagingProperties properties;

  public ProjectionRecoveryWorker(
      AuthorizationProjectionRecoveryCoordinator coordinator, MessagingProperties properties) {
    this.coordinator = coordinator;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.recovery-poll-interval:1000}")
  public void scheduledWork() {
    if (properties.recoveryWorkerEnabled()) {
      coordinator.ensureFullRecoveryInitialized();
      coordinator.requeueExpiredResults(Instant.now().minus(properties.recoveryResultTimeout()));
      coordinator.processNext();
    }
  }
}
