package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.UUID;

abstract class AbstractExecutionTransitionHandler {
  protected final AppointmentRepository appointments;
  protected final ProcessedMessageRepository inbox;
  protected final WashExecutionTransitionRepository transitions;
  protected final PendingWashExecutionEventRepository pending;
  protected final OutboxRepository outbox;

  protected AbstractExecutionTransitionHandler(AppointmentRepository appointments,
      ProcessedMessageRepository inbox, WashExecutionTransitionRepository transitions,
      PendingWashExecutionEventRepository pending, OutboxRepository outbox) {
    this.appointments = appointments;
    this.inbox = inbox;
    this.transitions = transitions;
    this.pending = pending;
    this.outbox = outbox;
  }

  protected ApplicationResult apply(ExecutionEvent event, Kind kind) {
    Instant now = event.occurredAt();
    if (!inbox.tryClaim(event.sourceEventId(), event.eventType(), now)) return ApplicationResult.duplicate();

    var latest = transitions.findLatestForExecution(event.washExecutionId()).orElse(null);
    if (latest != null && latest.executionVersion() >= event.executionVersion()) {
      inbox.markApplied(event.sourceEventId(), now);
      pending.discardBySourceEventId(event.sourceEventId(), now);
      return ApplicationResult.applied(event.appointmentId(), 0, "stale or equivalent execution transition ignored");
    }

    Appointment current = appointments.findByIdForUpdate(event.appointmentId()).orElse(null);
    if (current == null) {
      inbox.markApplied(event.sourceEventId(), now);
      pending.discardBySourceEventId(event.sourceEventId(), now);
      return ApplicationResult.rejected("APPOINTMENT_NOT_FOUND", "execution event references an unknown appointment");
    }

    if (kind == Kind.COMPLETED && current.status().name().equals("SCHEDULED")) {
      defer(event, "completion is causally waiting for execution start", now);
      return ApplicationResult.deferred("CAUSAL_DEPENDENCY", "completion was deferred until start is applied", current.appointmentId());
    }
    if (kind == Kind.CANCELLED && current.status().name().equals("SCHEDULED") && event.startedAt() != null) {
      defer(event, "cancellation is causally waiting for execution start", now);
      return ApplicationResult.deferred("CAUSAL_DEPENDENCY", "cancellation was deferred until start is applied", current.appointmentId());
    }
    if (kind == Kind.ENTRY_REJECTED && current.entryAuthorizedAt() != null) {
      inbox.markApplied(event.sourceEventId(), now);
      pending.discardBySourceEventId(event.sourceEventId(), now);
      return ApplicationResult.applied(current.appointmentId(), current.version(), "stale rejection ignored after authorization");
    }

    Instant actionAt = event.effectiveActionAt();
    Appointment changed = switch (kind) {
      case ENTRY_REJECTED -> current.status().name().equals("SCHEDULED")
          ? current.rejectEntry(event.reason() == null ? "entry rejected" : event.reason(), event.actorAccountId(), actionAt)
          : current;
      case STARTED -> current.status().name().equals("SCHEDULED") ? current.start(actionAt) : current;
      case COMPLETED -> current.status().name().equals("IN_PROGRESS") ? current.complete(event.actorAccountId(), actionAt) : current;
      case CANCELLED -> current.status().name().matches("SCHEDULED|IN_PROGRESS") ? cancelFromExecution(current, event, actionAt) : current;
    };

    if (changed != current && !appointments.updateIfVersion(changed, current.version())) {
      defer(event, "appointment changed while applying execution transition", now);
      return ApplicationResult.deferred("CONCURRENT_MODIFICATION", "execution transition will be retried", current.appointmentId());
    }

    transitions.record(new WashExecutionTransitionRepository.TransitionRecord(UUID.randomUUID(),
        event.washExecutionId(), event.appointmentId(), event.eventType(), event.executionVersion(),
        event.sourceEventId(), changed.status().name(), actionAt, now));
    inbox.markApplied(event.sourceEventId(), now);
    pending.discardBySourceEventId(event.sourceEventId(), now);

    if (changed != current) {
      ApplicationSupport.appendEvent(outbox, ApplicationSupport.inboundMetadata(event, now),
          "AppointmentStatusChanged", changed.appointmentId(), changed.version(),
          "{\"appointmentId\":\"" + changed.appointmentId() + "\",\"status\":\"" + changed.status()
              + "\",\"executionVersion\":" + event.executionVersion() + "}", now);
    }
    return ApplicationResult.applied(changed.appointmentId(), changed.version(), "execution transition applied");
  }

  private Appointment cancelFromExecution(Appointment current, ExecutionEvent event, Instant at) {
    if (event.cancellationType() != null && !"CLINIC".equals(event.cancellationType())) {
      throw new IllegalArgumentException("WashExecutionCancelled must carry CLINIC cancellationType");
    }
    if (event.cancellationSubreason() != null
        && !CancellationSubreason.CAPACITY_LOSS.name().equals(event.cancellationSubreason())) {
      throw new IllegalArgumentException("unsupported execution cancellation subreason");
    }
    return current.cancel(CancellationType.CLINIC, CancellationSubreason.CAPACITY_LOSS,
        event.reason(), event.actorAccountId(), at);
  }

  private void defer(ExecutionEvent event, String reason, Instant now) {
    pending.defer(new PendingWashExecutionEventRepository.PendingEvent(UUID.randomUUID(),
        event.sourceEventId(), event.appointmentId(), event.washExecutionId(), event.eventType(),
        event.executionVersion(), event.payloadOrEmpty(), "PENDING", 0, now, null, null));
    inbox.markDeferred(event.sourceEventId(), reason, now);
  }

  enum Kind { ENTRY_REJECTED, STARTED, COMPLETED, CANCELLED }
}
