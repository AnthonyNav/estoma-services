package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CancelAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelAppointmentTimeSlotHandler implements CancelAppointmentTimeSlotUseCase {
  private final AppointmentTimeSlotRepository slots;
  private final AppointmentRepository appointments;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public CancelAppointmentTimeSlotHandler(AppointmentTimeSlotRepository slots,
      AppointmentRepository appointments, CommandIdempotencyRepository idempotency,
      OutboxRepository outbox, AuthorizationProjectionRepository authorization) {
    this.slots = slots;
    this.appointments = appointments;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  @Transactional
  public ApplicationResult cancel(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claimClient(idempotency, outbox,
        command.metadata(), "CancelAppointmentTimeSlot",
        ApplicationSupport.fingerprint(command.slotId(), command.reason(), command.expectedVersion()), now);
    if (gate.isPresent()) return gate.get();

    ApplicationResult result;
    var slot = slots.findByIdForUpdate(command.slotId()).orElse(null);
    if (!policy.canAdministerSlots(command.metadata())) {
      result = ApplicationResult.rejected("FORBIDDEN", "actor cannot cancel slots");
    } else if (command.reason() == null || command.reason().isBlank()) {
      result = ApplicationResult.rejected("INVALID_REASON", "slot cancellation reason is required");
    } else if (slot == null) {
      result = ApplicationResult.rejected("SLOT_NOT_FOUND", "slot was not found");
    } else if (slot.version() != command.expectedVersion()) {
      result = ApplicationResult.rejected("VERSION_CONFLICT", "slot version changed");
    } else if (slot.isCancelled()) {
      result = ApplicationResult.applied(slot.appointmentTimeSlotId(), slot.version(), "slot already cancelled");
    } else {
      var cancelledSlot = slot.cancel();
      if (!slots.updateIfVersion(cancelledSlot, command.expectedVersion())) {
        result = ApplicationResult.rejected("VERSION_CONFLICT", "slot changed while cancelling");
      } else {
        int affected = 0;
        for (var appointment : appointments.findForSlot(command.slotId(), true)) {
          if (!appointment.status().name().equals("SCHEDULED") || appointment.entryAuthorizedAt() != null) continue;
          var cancelledAppointment = appointment.cancel(CancellationType.CLINIC,
              CancellationSubreason.CAPACITY_LOSS, command.reason(),
              command.metadata().actor().isAccount() ? command.metadata().actor().accountId() : null, now);
          if (!appointments.updateIfVersion(cancelledAppointment, appointment.version())) {
            throw new IllegalStateException("appointment changed while cancelling its time slot");
          }
          ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentCancelled",
              cancelledAppointment.appointmentId(), cancelledAppointment.version(),
              AppointmentEventPayloads.cancelled(cancelledAppointment), now);
          affected++;
        }
        ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentTimeSlotCancelled",
            cancelledSlot.appointmentTimeSlotId(), cancelledSlot.version(),
            AppointmentEventPayloads.timeSlotCancelled(cancelledSlot, command.reason()), now);
        result = ApplicationResult.applied(cancelledSlot.appointmentTimeSlotId(), cancelledSlot.version(),
            "slot cancelled; scheduled appointments cancelled=" + affected);
      }
    }
    ApplicationSupport.appendResult(outbox, command.metadata(), "CancelAppointmentTimeSlot", result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "CancelAppointmentTimeSlot", result, now);
    return result;
  }
}
