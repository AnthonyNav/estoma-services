package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Executes the destructive portion of capacity reconciliation against the latest projected capacity. */
@Service
public class CapacityMatchingCoordinator {
  private final AppointmentRepository appointments;
  private final AppointmentCapacityProjectionRepository capacity;
  private final OutboxRepository outbox;

  public CapacityMatchingCoordinator(AppointmentRepository appointments,
      AppointmentCapacityProjectionRepository capacity, OutboxRepository outbox) {
    this.appointments = appointments;
    this.capacity = capacity;
    this.outbox = outbox;
  }

  public int reconcile(UUID slotId, CommandMetadata metadata, Instant now) {
    var projection = capacity.findBySlotIdForUpdate(slotId)
        .orElseThrow(() -> new IllegalStateException("capacity projection disappeared before matching"));
    var all = appointments.findForSlot(slotId, true);
    int sharedDemand = (int) all.stream().filter(Appointment::isActive)
        .filter(a -> a.appointmentType().name().matches("NORMAL|JOURNEY")).count();
    int immunoDemand = (int) all.stream().filter(Appointment::isActive)
        .filter(a -> a.appointmentType().name().equals("IMMUNOCOMPROMISED")).count();
    int sharedCapacity = projection.normalCapacity() + projection.journeyCapacity();
    int cancelled = 0;

    // Repository order is createdAt DESC, appointmentId DESC: newest eligible appointments go first.
    for (Appointment candidate : all) {
      boolean sharedOverflow = sharedDemand > sharedCapacity
          && candidate.appointmentType().name().matches("NORMAL|JOURNEY");
      boolean immunoOverflow = immunoDemand > projection.immunocompromisedCapacity()
          && candidate.appointmentType().name().equals("IMMUNOCOMPROMISED");
      if ((!sharedOverflow && !immunoOverflow) || !candidate.status().name().equals("SCHEDULED")
          || candidate.entryAuthorizedAt() != null) continue;

      var changed = candidate.cancel(CancellationType.CLINIC, CancellationSubreason.CAPACITY_LOSS,
          "capacity loss", null, now);
      if (!appointments.updateIfVersion(changed, candidate.version())) {
        throw new IllegalStateException("appointment changed while reconciling capacity");
      }
      if (sharedOverflow) sharedDemand--; else immunoDemand--;
      cancelled++;
      ApplicationSupport.appendEvent(outbox, metadata, "AppointmentCancelled",
          changed.appointmentId(), changed.version(), AppointmentEventPayloads.cancelled(changed), now);
    }
    return cancelled;
  }
}
