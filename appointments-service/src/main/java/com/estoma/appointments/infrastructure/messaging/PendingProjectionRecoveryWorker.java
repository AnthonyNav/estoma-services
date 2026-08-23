package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.out.PendingProjectionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replays locally persisted partial Events after the authoritative snapshot establishes their base. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "recovery-worker-enabled", havingValue = "true")
public class PendingProjectionRecoveryWorker {
  private static final int BATCH_SIZE = 50;
  private static final int MAX_BATCHES_PER_PASS = 100;

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

  /**
   * Drains more than one claim batch, but processes each source Event at most once per pass.
   * An Event whose base is still absent is moved briefly into the future so it cannot spin forever
   * and the caller can observe that unresolved work remains.
   */
  void replay(String projection) {
    String worker = UUID.randomUUID().toString();
    Set<UUID> attempted = new HashSet<>();

    for (int batch = 0; batch < MAX_BATCHES_PER_PASS; batch++) {
      Instant now = clock.instant();
      var rows = pending.claim(projection, BATCH_SIZE, worker, now, now.plusSeconds(30));
      if (rows.isEmpty()) return;

      for (var row : rows) {
        if (!attempted.add(row.sourceEventId())) {
          pending.fail(row.pendingEventId(), worker,
              "projection dependency remains unresolved after authoritative snapshot",
              clock.instant().plusSeconds(5));
          continue;
        }
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

    if (pending.hasOpen(projection)) {
      recovery.degraded(projection,
          "deferred projection replay exceeded the bounded drain pass");
    }
  }
}
