package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.contracts.MessagingNames;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Durable bootstrap request for owner snapshots; RabbitMQ remains a transport, not an event store. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "recovery-worker-enabled", havingValue = "true")
public class ProjectionRecoveryWorker {
  private final ProjectionRecoveryCoordinator recovery;
  private final OutboxRepository outbox;
  private final Clock clock;
  public ProjectionRecoveryWorker(ProjectionRecoveryCoordinator recovery, OutboxRepository outbox, Clock clock) {
    this.recovery = recovery; this.outbox = outbox; this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.recovery-poll-interval:30s}")
  public void scheduledBootstrap() { bootstrapAdministrationIfNeeded(); }

  @Transactional
  public void bootstrapAdministrationIfNeeded() {
    if ("READY".equals(recoveryState("wash-administration"))) return;
    recovery.rebuilding("wash-administration");
    Instant now = clock.instant();
    outbox.append(new OutboxRepository.OutboxMessage(UUID.randomUUID(), "GetWashAdministrationConfiguration", 1, "QUERY",
        null, null, UUID.randomUUID(), null, "{\"referenceDate\":\"" + LocalDate.now(clock) + "\"}", now, now, "PENDING", 0, null, null));
  }

  private String recoveryState(String name) {
    // The coordinator is deliberately authoritative; querying through its public readiness API avoids
    // making transport adapters depend on a concrete persistence repository.
    return recovery.criticalReady() ? "READY" : "NOT_READY";
  }
}
