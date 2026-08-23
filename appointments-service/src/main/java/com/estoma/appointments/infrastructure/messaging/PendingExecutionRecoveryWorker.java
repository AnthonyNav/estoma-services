package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleWashEntryAuthorizedUseCase;
import com.estoma.appointments.application.port.in.HandleWashEntryRejectedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCancelledUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCompletedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionStartedUseCase;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "recovery-worker-enabled", havingValue = "true")
public final class PendingExecutionRecoveryWorker {
  private final PendingWashExecutionEventRepository pending;
  private final HandleWashEntryRejectedUseCase rejected;
  private final HandleWashExecutionStartedUseCase started;
  private final HandleWashExecutionCompletedUseCase completed;
  private final HandleWashExecutionCancelledUseCase cancelled;
  private final HandleWashEntryAuthorizedUseCase authorized;
  public PendingExecutionRecoveryWorker(PendingWashExecutionEventRepository pending,
      HandleWashEntryRejectedUseCase rejected, HandleWashExecutionStartedUseCase started,
      HandleWashExecutionCompletedUseCase completed, HandleWashExecutionCancelledUseCase cancelled,
      HandleWashEntryAuthorizedUseCase authorized) {
    this.pending = pending; this.rejected = rejected; this.started = started; this.completed = completed; this.cancelled = cancelled; this.authorized = authorized;
  }
  @Scheduled(fixedDelayString = "${estoma.messaging.pending-event-poll-interval:5s}")
  public void scheduledReplay() { replay(Instant.now()); }
  public void replay(Instant now) {
    String worker = UUID.randomUUID().toString();
    List<PendingWashExecutionEventRepository.PendingEvent> events = pending.claim(50, worker, now, now.plusSeconds(30));
    for (var value : events) {
      ExecutionEvent event = new ExecutionEvent(value.sourceEventId(), value.sourceEventId(), value.washExecutionId(), value.appointmentId(), value.eventType(), value.executionVersion(), now, null, null, null, value.payloadJson());
      var result = switch (value.eventType()) {
        case "WashEntryRejected" -> rejected.handle(event);
        case "WashExecutionStarted" -> started.handle(event);
        case "WashExecutionCompleted" -> completed.handle(event);
        case "WashExecutionCancelled" -> cancelled.handle(event);
        case "WashEntryAuthorized" -> authorized.handle(event);
        default -> throw new IllegalArgumentException("unsupported pending event " + value.eventType());
      };
      if (result.terminal()) pending.complete(value.pendingEventId(), worker, now);
    }
  }
}
