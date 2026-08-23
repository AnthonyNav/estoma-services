package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleWashEntryAuthorizedUseCase;
import com.estoma.appointments.application.port.in.HandleWashEntryRejectedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCancelledUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionCompletedUseCase;
import com.estoma.appointments.application.port.in.HandleWashExecutionStartedUseCase;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "recovery-worker-enabled", havingValue = "true")
public final class PendingExecutionRecoveryWorker {
  private final PendingWashExecutionEventRepository pending;
  private final HandleWashEntryRejectedUseCase rejected;
  private final HandleWashExecutionStartedUseCase started;
  private final HandleWashExecutionCompletedUseCase completed;
  private final HandleWashExecutionCancelledUseCase cancelled;
  private final HandleWashEntryAuthorizedUseCase authorized;
  private final EnvelopeJsonCodec codec;
  private final Clock clock;

  public PendingExecutionRecoveryWorker(PendingWashExecutionEventRepository pending,
      HandleWashEntryRejectedUseCase rejected, HandleWashExecutionStartedUseCase started,
      HandleWashExecutionCompletedUseCase completed, HandleWashExecutionCancelledUseCase cancelled,
      HandleWashEntryAuthorizedUseCase authorized, EnvelopeJsonCodec codec, Clock clock) {
    this.pending = pending; this.rejected = rejected; this.started = started;
    this.completed = completed; this.cancelled = cancelled; this.authorized = authorized;
    this.codec = codec; this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.pending-event-poll-interval:5s}")
  public void scheduledReplay() { replay(clock.instant()); }

  public void replay(Instant now) {
    String worker = UUID.randomUUID().toString();
    List<PendingWashExecutionEventRepository.PendingEvent> events = pending.claim(
        50, worker, now, now.plusSeconds(30));
    for (var value : events) {
      try {
        ExecutionEvent event = rehydrate(value, now);
        var result = switch (value.eventType()) {
          case "WashEntryRejected" -> rejected.handle(event);
          case "WashExecutionStarted" -> started.handle(event);
          case "WashExecutionCompleted" -> completed.handle(event);
          case "WashExecutionCancelled" -> cancelled.handle(event);
          case "WashEntryAuthorized" -> authorized.handle(event);
          default -> throw new IllegalArgumentException("unsupported pending event " + value.eventType());
        };
        if (result.terminal()) pending.complete(value.pendingEventId(), worker, clock.instant());
      } catch (RuntimeException ignoredUntilLeaseExpires) {
        // The PROCESSING lease makes the row retryable without deleting the authoritative payload.
      }
    }
  }

  private ExecutionEvent rehydrate(PendingWashExecutionEventRepository.PendingEvent value, Instant now) {
    JsonNode p = codec.readTree(value.payloadJson());
    return switch (value.eventType()) {
      case "WashEntryAuthorized" -> event(value, now, null, instant(p,"decidedAt"),
          optionalUuid(p,"decidedByAccountId"), null, null, null, null);
      case "WashEntryRejected" -> event(value, now, null, instant(p,"decidedAt"),
          optionalUuid(p,"decidedByAccountId"), null, null, null, text(p,"rejectionReason"));
      case "WashExecutionStarted" -> event(value, now, null, instant(p,"startedAt"),
          null, null, null, null, null);
      case "WashExecutionCompleted" -> event(value, now, null, instant(p,"completedAt"),
          optionalUuid(p,"completedByAccountId"), null, null, null, null);
      case "WashExecutionCancelled" -> event(value, now, null, instant(p,"cancelledAt"),
          optionalUuid(p,"cancelledByAccountId"), optionalInstant(p,"startedAt"),
          text(p,"cancellationType"), text(p,"cancellationSubreason"),
          optionalText(p,"cancellationReason"));
      default -> throw new IllegalArgumentException("unsupported pending event " + value.eventType());
    };
  }

  private static ExecutionEvent event(PendingWashExecutionEventRepository.PendingEvent value,
      Instant now, Instant arrivedAt, Instant actionAt, UUID actor, Instant startedAt,
      String cancellationType, String cancellationSubreason, String reason) {
    return new ExecutionEvent(value.sourceEventId(), value.sourceEventId(), value.washExecutionId(),
        value.appointmentId(), value.eventType(), value.executionVersion(), now, arrivedAt, actionAt,
        actor, startedAt, cancellationType, cancellationSubreason, reason, value.payloadJson());
  }

  private static String text(JsonNode p, String field) {
    String value = optionalText(p, field);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    return value;
  }
  private static String optionalText(JsonNode p, String field) {
    return !p.has(field) || p.path(field).isNull() || !p.path(field).isTextual()
        ? null : p.path(field).asText();
  }
  private static Instant instant(JsonNode p, String field) {
    Instant value = optionalInstant(p, field);
    if (value == null) throw new IllegalArgumentException(field + " is required");
    return value;
  }
  private static Instant optionalInstant(JsonNode p, String field) {
    String value = optionalText(p, field);
    return value == null ? null : Instant.parse(value);
  }
  private static UUID optionalUuid(JsonNode p, String field) {
    String value = optionalText(p, field);
    return value == null ? null : UUID.fromString(value);
  }
}
