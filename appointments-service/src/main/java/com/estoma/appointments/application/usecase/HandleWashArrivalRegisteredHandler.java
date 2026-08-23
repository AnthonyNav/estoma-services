package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.port.in.HandleWashArrivalRegisteredUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class HandleWashArrivalRegisteredHandler implements HandleWashArrivalRegisteredUseCase {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final WashExecutionEvidenceRepository evidence;
  private final ProcessedMessageRepository inbox;

  public HandleWashArrivalRegisteredHandler(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots, WashExecutionEvidenceRepository evidence,
      ProcessedMessageRepository inbox) {
    this.appointments = appointments;
    this.slots = slots;
    this.evidence = evidence;
    this.inbox = inbox;
  }

  @Override
  @Transactional
  public ApplicationResult handle(ExecutionEvent event) {
    Instant now = event.occurredAt();
    if (!inbox.tryClaim(event.sourceEventId(), event.eventType(), now)) return ApplicationResult.duplicate();
    var appointment = appointments.findByIdForUpdate(event.appointmentId()).orElse(null);
    var slot = appointment == null ? null : slots.findById(appointment.appointmentTimeSlotId()).orElse(null);
    Instant arrivedAt = event.effectiveArrivalAt();
    if (appointment == null || slot == null) {
      inbox.markApplied(event.sourceEventId(), now);
      return ApplicationResult.rejected("APPOINTMENT_NOT_FOUND", "arrival references an unknown appointment");
    }
    if (arrivedAt.isBefore(slot.startsAt()) || arrivedAt.isAfter(slot.arrivalToleranceUntil())) {
      inbox.markApplied(event.sourceEventId(), now);
      return ApplicationResult.rejected("INVALID_ARRIVAL_EVIDENCE", "arrival is outside the slot tolerance window");
    }
    evidence.recordArrival(new WashExecutionEvidenceRepository.EvidenceRecord(UUID.randomUUID(),
        event.washExecutionId(), event.appointmentId(), arrivedAt, event.executionVersion(), null, null,
        event.sourceEventId(), "WashArrivalRegistered", now));
    inbox.markApplied(event.sourceEventId(), now);
    return ApplicationResult.applied(event.appointmentId(), appointment.version(), "arrival evidence recorded");
  }
}
