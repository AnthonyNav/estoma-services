package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CancelAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelAppointmentTimeSlotHandler implements CancelAppointmentTimeSlotUseCase {
  private final AppointmentTimeSlotRepository slots;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public CancelAppointmentTimeSlotHandler(AppointmentTimeSlotRepository slots,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox,
      AuthorizationProjectionRepository authorization) {
    this.slots = slots;
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
        ApplicationSupport.fingerprint(command.slotId(), command.expectedVersion()), now);
    if (gate.isPresent()) return gate.get();

    ApplicationResult result;
    var slot = slots.findByIdForUpdate(command.slotId()).orElse(null);
    if (!policy.canAdministerSlots(command.metadata())) {
      result = ApplicationResult.rejected("FORBIDDEN", "actor cannot cancel slots");
    } else if (slot == null) {
      result = ApplicationResult.rejected("SLOT_NOT_FOUND", "slot was not found");
    } else if (slot.version() != command.expectedVersion()) {
      result = ApplicationResult.rejected("VERSION_CONFLICT", "slot version changed");
    } else if (slot.isCancelled()) {
      result = ApplicationResult.applied(slot.appointmentTimeSlotId(), slot.version(), "slot already cancelled");
    } else {
      var cancelled = slot.cancel();
      if (!slots.updateIfVersion(cancelled, command.expectedVersion())) {
        result = ApplicationResult.rejected("VERSION_CONFLICT", "slot changed while cancelling");
      } else {
        ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentTimeSlotCancelled",
            cancelled.appointmentTimeSlotId(), cancelled.version(),
            "{\"appointmentTimeSlotId\":\"" + cancelled.appointmentTimeSlotId()
                + "\",\"status\":\"CANCELLED\",\"version\":" + cancelled.version() + "}", now);
        result = ApplicationResult.applied(cancelled.appointmentTimeSlotId(), cancelled.version(), "slot cancelled");
      }
    }
    ApplicationSupport.appendResult(outbox, command.metadata(), "CancelAppointmentTimeSlot", result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "CancelAppointmentTimeSlot", result, now);
    return result;
  }
}
