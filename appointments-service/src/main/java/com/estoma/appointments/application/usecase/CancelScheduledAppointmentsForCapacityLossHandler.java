package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CancelScheduledAppointmentsForCapacityLossUseCase;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelScheduledAppointmentsForCapacityLossHandler
    implements CancelScheduledAppointmentsForCapacityLossUseCase {
  private static final String CAPACITY_LOSS = "CAPACITY_LOSS";
  private final AppointmentRepository appointments;
  private final AppointmentCapacityProjectionRepository capacity;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;

  public CancelScheduledAppointmentsForCapacityLossHandler(AppointmentRepository appointments,
      AppointmentCapacityProjectionRepository capacity, CommandIdempotencyRepository idempotency,
      OutboxRepository outbox) {
    this.appointments = appointments;
    this.capacity = capacity;
    this.idempotency = idempotency;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public ApplicationResult cancel(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "CancelScheduledAppointmentsForCapacityLoss", ApplicationSupport.fingerprint(
            command.sourceEventId(), command.slotId()), now);
    if (gate.isPresent()) return gate.get();

    var projection = capacity.findBySlotIdForUpdate(command.slotId()).orElse(null);
    if (projection == null) {
      ApplicationResult deferred = ApplicationResult.deferred("CAPACITY_NOT_READY",
          "capacity projection is not available", command.slotId());
      ApplicationSupport.releaseDeferred(idempotency, command.metadata(),
          "CancelScheduledAppointmentsForCapacityLoss", deferred);
      return deferred;
    }

    var all = appointments.findForSlot(command.slotId(), true);
    int sharedDemand = (int) all.stream().filter(Appointment::isActive)
        .filter(a -> a.appointmentType().name().matches("NORMAL|JOURNEY")).count();
    int immunoDemand = (int) all.stream().filter(Appointment::isActive)
        .filter(a -> a.appointmentType().name().equals("IMMUNOCOMPROMISED")).count();
    int sharedCapacity = projection.normalCapacity() + projection.journeyCapacity();
    int cancelled = 0;

    for (Appointment candidate : all) {
      boolean sharedOverflow = sharedDemand > sharedCapacity
          && candidate.appointmentType().name().matches("NORMAL|JOURNEY");
      boolean immunoOverflow = immunoDemand > projection.immunocompromisedCapacity()
          && candidate.appointmentType().name().equals("IMMUNOCOMPROMISED");
      if ((!sharedOverflow && !immunoOverflow) || !candidate.status().name().equals("SCHEDULED")
          || candidate.entryAuthorizedAt() != null) continue;
      var current = appointments.findByIdForUpdate(candidate.appointmentId()).orElse(candidate);
      if (!current.status().name().equals("SCHEDULED") || current.entryAuthorizedAt() != null) continue;
      var changed = current.cancel(CancellationType.CLINIC, CAPACITY_LOSS,
          "capacity loss", null, now);
      if (!appointments.updateIfVersion(changed, current.version())) continue;
      if (sharedOverflow) sharedDemand--; else immunoDemand--;
      cancelled++;
      ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentCancelled",
          changed.appointmentId(), changed.version(), AppointmentEventPayloads.cancelled(changed), now);
    }

    ApplicationResult result = ApplicationResult.applied(command.slotId(), projection.version(),
        "capacity loss cancelled " + cancelled + " scheduled appointments");
    ApplicationSupport.complete(idempotency, command.metadata(),
        "CancelScheduledAppointmentsForCapacityLoss", result, now);
    return result;
  }
}
