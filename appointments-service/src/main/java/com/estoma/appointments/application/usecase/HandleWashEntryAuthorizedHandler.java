package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleWashEntryAuthorizedUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import com.estoma.appointments.domain.error.DomainViolationException;
import com.estoma.appointments.domain.service.AppointmentEvidenceMerger;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class HandleWashEntryAuthorizedHandler implements HandleWashEntryAuthorizedUseCase {
  private final AppointmentRepository appointments;
  private final WashExecutionEvidenceRepository evidence;
  private final ProcessedMessageRepository inbox;
  private final PendingWashExecutionEventRepository pending;

  public HandleWashEntryAuthorizedHandler(AppointmentRepository appointments,
      WashExecutionEvidenceRepository evidence, ProcessedMessageRepository inbox,
      PendingWashExecutionEventRepository pending) {
    this.appointments = appointments;
    this.evidence = evidence;
    this.inbox = inbox;
    this.pending = pending;
  }

  @Override
  @Transactional
  public ApplicationResult handle(ExecutionEvent event) {
    Instant now = event.occurredAt();
    if (!inbox.tryClaim(event.sourceEventId(), event.eventType(), now)) return ApplicationResult.duplicate();
    var appointment = appointments.findByIdForUpdate(event.appointmentId()).orElse(null);
    if (appointment == null) {
      inbox.markApplied(event.sourceEventId(), now);
      pending.discardBySourceEventId(event.sourceEventId(), now);
      return ApplicationResult.rejected("APPOINTMENT_NOT_FOUND", "entry authorization references an unknown appointment");
    }
    Instant decidedAt = event.effectiveActionAt();
    evidence.recordEntryAuthorization(new WashExecutionEvidenceRepository.EvidenceRecord(UUID.randomUUID(),
        event.washExecutionId(), event.appointmentId(), null, null, decidedAt, event.executionVersion(),
        event.sourceEventId(), "WashEntryAuthorized", now));
    if (appointment.entryAuthorizedAt() == null) {
      try {
        var authorized = AppointmentEvidenceMerger.addEntryAuthorization(appointment, decidedAt);
        if (!appointments.updateIfVersion(authorized, appointment.version())) {
          pending.defer(new PendingWashExecutionEventRepository.PendingEvent(UUID.randomUUID(),
              event.sourceEventId(), event.appointmentId(), event.washExecutionId(), event.eventType(),
              event.executionVersion(), event.payloadOrEmpty(), "PENDING", 0, now, null, null,
              event.correlationId()));
          inbox.markDeferred(event.sourceEventId(), "appointment changed while applying entry authorization", now);
          return ApplicationResult.deferred("CONCURRENT_MODIFICATION", "entry authorization will be retried", appointment.appointmentId());
        }
        appointment = authorized;
      } catch (DomainViolationException contradiction) {
        inbox.markApplied(event.sourceEventId(), now);
        pending.discardBySourceEventId(event.sourceEventId(), now);
        return ApplicationResult.rejected("ENTRY_AUTHORIZATION_CONFLICT", contradiction.getMessage());
      }
    }
    inbox.markApplied(event.sourceEventId(), now);
    pending.discardBySourceEventId(event.sourceEventId(), now);
    return ApplicationResult.applied(appointment.appointmentId(), appointment.version(), "entry authorization evidence recorded");
  }
}
