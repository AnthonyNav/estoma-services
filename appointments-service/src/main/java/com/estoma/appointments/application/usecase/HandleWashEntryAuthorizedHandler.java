package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleWashEntryAuthorizedUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class HandleWashEntryAuthorizedHandler implements HandleWashEntryAuthorizedUseCase {
  private final AppointmentRepository appointments;
  private final WashExecutionEvidenceRepository evidence;
  private final ProcessedMessageRepository inbox;

  public HandleWashEntryAuthorizedHandler(AppointmentRepository appointments,
      WashExecutionEvidenceRepository evidence, ProcessedMessageRepository inbox) {
    this.appointments = appointments;
    this.evidence = evidence;
    this.inbox = inbox;
  }

  @Override
  @Transactional
  public ApplicationResult handle(ExecutionEvent event) {
    Instant now = event.occurredAt();
    if (!inbox.tryClaim(event.sourceEventId(), event.eventType(), now)) return ApplicationResult.duplicate();
    var appointment = appointments.findByIdForUpdate(event.appointmentId()).orElse(null);
    if (appointment == null) {
      inbox.markApplied(event.sourceEventId(), now);
      return ApplicationResult.rejected("APPOINTMENT_NOT_FOUND", "entry authorization references an unknown appointment");
    }
    Instant decidedAt = event.effectiveActionAt();
    evidence.recordEntryAuthorization(new WashExecutionEvidenceRepository.EvidenceRecord(UUID.randomUUID(),
        event.washExecutionId(), event.appointmentId(), null, null, decidedAt, event.executionVersion(),
        event.sourceEventId(), "WashEntryAuthorized", now));
    if (!appointment.isTerminal() && appointment.entryAuthorizedAt() == null) {
      var authorized = appointment.authorizeEntry(decidedAt);
      if (!appointments.updateIfVersion(authorized, appointment.version())) {
        inbox.markDeferred(event.sourceEventId(), "appointment changed while applying entry authorization", now);
        return ApplicationResult.deferred("CONCURRENT_MODIFICATION", "entry authorization will be retried", appointment.appointmentId());
      }
      appointment = authorized;
    }
    inbox.markApplied(event.sourceEventId(), now);
    return ApplicationResult.applied(appointment.appointmentId(), appointment.version(), "entry authorization evidence recorded");
  }
}
