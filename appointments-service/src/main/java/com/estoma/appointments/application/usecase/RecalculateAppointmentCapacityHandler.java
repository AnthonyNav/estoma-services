package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.RecalculateAppointmentCapacityForTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Re-evaluates derived demand while holding the slot/capacity locks. */
@Service
public final class RecalculateAppointmentCapacityHandler implements RecalculateAppointmentCapacityForTimeSlotUseCase {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final AppointmentCapacityProjectionRepository capacity;
  private final ExceptionalAppointmentAuthorizationRepository authorizations;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;

  public RecalculateAppointmentCapacityHandler(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots, AppointmentCapacityProjectionRepository capacity,
      ExceptionalAppointmentAuthorizationRepository authorizations,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox) {
    this.appointments = appointments;
    this.slots = slots;
    this.capacity = capacity;
    this.authorizations = authorizations;
    this.idempotency = idempotency;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public ApplicationResult recalculate(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "RecalculateAppointmentCapacityForTimeSlot", ApplicationSupport.fingerprint(command.sourceEventId(), command.slotId()), now);
    if (gate.isPresent()) return gate.get();
    var slot = slots.findByIdForUpdate(command.slotId()).orElse(null);
    var projection = capacity.findBySlotIdForUpdate(command.slotId()).orElse(null);
    if (slot == null || projection == null) {
      ApplicationResult missing = ApplicationResult.rejected("CAPACITY_NOT_READY", "slot capacity cannot be recalculated yet");
      ApplicationSupport.appendResult(outbox, command.metadata(), missing, now);
      ApplicationSupport.complete(idempotency, command.metadata(), "RecalculateAppointmentCapacityForTimeSlot", missing, now);
      return missing;
    }
    ArrayList<Appointment> active = new ArrayList<>(appointments.findForSlot(command.slotId(), true).stream()
        .filter(Appointment::isActive).toList());
    int sharedCapacity = projection.normalCapacity() + projection.journeyCapacity();
    int sharedDemand = (int) active.stream().filter(a -> a.appointmentType().name().matches("NORMAL|JOURNEY")).count();
    int immunoDemand = (int) active.stream().filter(a -> a.appointmentType().name().equals("IMMUNOCOMPROMISED")).count();
    ArrayList<Appointment> candidates = new ArrayList<>(active);
    for (Appointment candidate : active) {
      boolean overflow = sharedDemand > sharedCapacity && candidate.appointmentType().name().matches("NORMAL|JOURNEY")
          || immunoDemand > projection.immunocompromisedCapacity()
              && candidate.appointmentType().name().equals("IMMUNOCOMPROMISED");
      if (!overflow || candidate.status().name().equals("IN_PROGRESS") || candidate.entryAuthorizedAt() != null) continue;
      var current = appointments.findByIdForUpdate(candidate.appointmentId()).orElse(candidate);
      if (current.status().name().equals("SCHEDULED") && current.entryAuthorizedAt() == null) {
        var cancelled = current.cancel(CancellationType.CLINIC, CancellationSubreason.CAPACITY_LOSS,
            "capacity loss", command.metadata().actor().accountId(), now);
        if (!appointments.updateIfVersion(cancelled, current.version())) continue;
        candidates.remove(candidate);
        if (candidate.appointmentType().name().matches("NORMAL|JOURNEY")) sharedDemand--;
        else immunoDemand--;
        if (cancelled.exceptionalAuthorizationId() != null) {
          authorizations.findByIdForUpdate(cancelled.exceptionalAuthorizationId());
        }
        ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentCancelled",
            cancelled.appointmentId(), cancelled.version(), "{\"appointmentId\":\""
                + cancelled.appointmentId() + "\",\"subreason\":\"CAPACITY_LOSS\"}", now);
      }
    }
    ApplicationResult result = ApplicationResult.applied(slot.appointmentTimeSlotId(), projection.version(),
        "appointment capacity recalculated");
    ApplicationSupport.appendResult(outbox, command.metadata(), result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "RecalculateAppointmentCapacityForTimeSlot", result, now);
    return result;
  }
}
