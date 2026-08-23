package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.out.PendingProjectionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replays locally persisted partial Events after the authoritative snapshot establishes their base. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "recovery-worker-enabled", havingValue = "true")
public class PendingProjectionRecoveryWorker {
  private final PendingProjectionEventRepository pending;
  private final ProcessedMessageRepository inbox;
  private final ProjectionEventDispatcher dispatcher;
  private final ProjectionRecoveryCoordinator recovery;
  private final EnvelopeJsonCodec codec;
  private final Clock clock;

  public PendingProjectionRecoveryWorker(PendingProjectionEventRepository pending,
      ProcessedMessageRepository inbox, ProjectionEventDispatcher dispatcher,
      ProjectionRecoveryCoordinator recovery, EnvelopeJsonCodec codec, Clock clock) {
    this.pending = pending;
    this.inbox = inbox;
    this.dispatcher = dispatcher;
    this.recovery = recovery;
    this.codec = codec;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.pending-projection-poll-interval:2s}")
  public void replayReadyProjections() {
    for (String projection : ProjectionRecoveryCoordinator.CRITICAL_PROJECTIONS) {
      if (!recovery.isReady(projection)) continue;
      replay(projection);
    }
  }

  void replay(String projection) {
    String worker = UUID.randomUUID().toString();
    Instant now = clock.instant();
    var rows = pending.claim(projection, 50, worker, now, now.plusSeconds(30));
    for (var row : rows) {
      try {
        dispatcher.dispatch(codec.decode(codec.readTree(row.envelopeJson())));
        if (!inbox.wasProcessed(row.sourceEventId()) && recovery.isReady(projection)) {
          recovery.degraded(projection,
              "authoritative snapshot did not satisfy deferred event " + row.messageType());
        }
      } catch (RuntimeException failure) {
        pending.fail(row.pendingEventId(), worker, failure.getMessage(), clock.instant().plusSeconds(5));
        recovery.degraded(projection, "deferred projection replay failed: " + failure.getMessage());
      }
    }
  }
}
