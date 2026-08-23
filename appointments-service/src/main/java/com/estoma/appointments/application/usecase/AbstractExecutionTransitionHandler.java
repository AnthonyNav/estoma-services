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
    Appointment current = appointments.findByIdForUpdate(event.appointmentId()).orElse(null);
    if (current == null) {
      inbox.markApplied(event.sourceEventId(), now);
      return ApplicationResult.rejected("APPOINTMENT_NOT_FOUND", "execution event references an unknown appointment");
    }
    if (kind == Kind.COMPLETED && current.status().name().equals("SCHEDULED")) {
      pending.defer(new PendingWashExecutionEventRepository.PendingEvent(UUID.randomUUID(), event.sourceEventId(),
          event.appointmentId(), event.washExecutionId(), event.eventType(), event.executionVersion(),
          event.payloadOrEmpty(), "PENDING", 0, now, null, null));
      inbox.markDeferred(event.sourceEventId(), "completion is causally waiting for execution start", now);
      return ApplicationResult.deferred("CAUSAL_DEPENDENCY", "completion was deferred until start is applied", current.appointmentId());
    }
    if (kind == Kind.ENTRY_REJECTED && current.entryAuthorizedAt() != null) {
      inbox.markApplied(event.sourceEventId(), now);
      return ApplicationResult.applied(current.appointmentId(), current.version(), "stale rejection ignored after authorization");
    }
    Appointment changed = switch (kind) {
      case ENTRY_REJECTED -> current.status().name().equals("SCHEDULED")
          ? current.rejectEntry(event.reason() == null ? "entry rejected" : event.reason(), null, event.occurredAt()) : current;
      case STARTED -> current.status().name().equals("SCHEDULED")
          ? current.start(event.occurredAt()) : current;
      case COMPLETED -> current.status().name().equals("IN_PROGRESS")
          ? current.complete(event.occurredAt()) : current;
      case CANCELLED -> current.status().name().matches("SCHEDULED|IN_PROGRESS")
          ? current.cancel(CancellationType.CLINIC, CancellationSubreason.CAPACITY_LOSS,
              event.reason() == null ? "execution cancelled" : event.reason(), event.washExecutionId(), event.occurredAt()) : current;
    };
    if (changed != current && !appointments.updateIfVersion(changed, current.version())) {
      inbox.markDeferred(event.sourceEventId(), "appointment changed while applying execution transition", now);
      return ApplicationResult.deferred("CONCURRENT_MODIFICATION", "execution transition will be retried", current.appointmentId());
    }
    transitions.record(new WashExecutionTransitionRepository.TransitionRecord(UUID.randomUUID(),
        event.washExecutionId(), event.appointmentId(), event.eventType(), event.executionVersion(),
        event.sourceEventId(), changed.status().name(), event.occurredAt(), now));
    inbox.markApplied(event.sourceEventId(), now);
    if (changed != current) {
      ApplicationSupport.appendEvent(outbox, ApplicationSupport.inboundMetadata(event, now),
          "AppointmentStatusChanged", changed.appointmentId(), changed.version(),
          "{\"appointmentId\":\"" + changed.appointmentId() + "\",\"status\":\""
              + changed.status() + "\",\"executionVersion\":" + event.executionVersion() + "}", now);
    }
    return ApplicationResult.applied(changed.appointmentId(), changed.version(), "execution transition applied");
  }

  enum Kind { ENTRY_REJECTED, STARTED, COMPLETED, CANCELLED }
}
