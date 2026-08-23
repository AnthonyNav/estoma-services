package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CreateAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class CreateAppointmentTimeSlotHandler implements CreateAppointmentTimeSlotUseCase {
  private final AppointmentTimeSlotRepository slots;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public CreateAppointmentTimeSlotHandler(AppointmentTimeSlotRepository slots,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox,
      AuthorizationProjectionRepository authorization) {
    this.slots = slots;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  @Transactional
  public ApplicationResult create(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "CreateAppointmentTimeSlot", ApplicationSupport.fingerprint(command.slotId(),
            command.sourceScheduleId(), command.serviceDate(), command.startsAt(), command.endsAt()), now);
    if (gate.isPresent()) return gate.get();

    ApplicationResult result;
    try {
      boolean authorized = policy.canAdministerSlots(command.metadata())
          || policy.isInternalAppointmentsProcess(command.metadata());
      if (!authorized) {
        result = ApplicationResult.rejected("FORBIDDEN", "actor cannot create slots");
      } else if (slots.findById(command.slotId()).isPresent()) {
        result = ApplicationResult.rejected("SLOT_ALREADY_EXISTS", "slot already exists");
      } else {
        AppointmentTimeSlot slot = AppointmentTimeSlot.create(command.slotId(), command.sourceScheduleId(),
            command.serviceDate(), command.startsAt(), command.endsAt(), command.timezone(),
            command.bookingDeadlineAt(), command.cancellationDeadlineAt(), command.arrivalToleranceUntil());
        slots.save(slot);
        ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentTimeSlotCreated",
            slot.appointmentTimeSlotId(), slot.version(),
            "{\"slotId\":\"" + slot.appointmentTimeSlotId() + "\"}", now);
        result = ApplicationResult.applied(slot.appointmentTimeSlotId(), slot.version(),
            "appointment time slot created");
      }
    } catch (IllegalArgumentException exception) {
      result = ApplicationResult.rejected("INVALID_SLOT", exception.getMessage());
    }
    ApplicationSupport.appendResult(outbox, command.metadata(), result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "CreateAppointmentTimeSlot", result, now);
    return result;
  }
}
