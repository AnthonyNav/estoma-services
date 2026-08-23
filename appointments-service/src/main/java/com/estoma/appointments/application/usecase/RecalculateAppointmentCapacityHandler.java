package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.RecalculateAppointmentCapacityForTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.application.port.out.WashExecutionOperationalProjectionRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds usable slot capacity from local owner projections and reconciles excess demand. */
@Service
public final class RecalculateAppointmentCapacityHandler
    implements RecalculateAppointmentCapacityForTimeSlotUseCase {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final AppointmentCapacityProjectionRepository capacity;
  private final WashAdministrationProjectionRepository administration;
  private final WashExecutionOperationalProjectionRepository execution;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;

  public RecalculateAppointmentCapacityHandler(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots, AppointmentCapacityProjectionRepository capacity,
      WashAdministrationProjectionRepository administration,
      WashExecutionOperationalProjectionRepository execution,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox) {
    this.appointments = appointments;
    this.slots = slots;
    this.capacity = capacity;
    this.administration = administration;
    this.execution = execution;
    this.idempotency = idempotency;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public ApplicationResult recalculate(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "RecalculateAppointmentCapacityForTimeSlot", ApplicationSupport.fingerprint(
            command.sourceEventId(), command.slotId(), command.expectedProjectionVersion()), now);
    if (gate.isPresent()) return gate.get();

    var slot = slots.findByIdForUpdate(command.slotId()).orElse(null);
    if (slot == null) return terminal(command, ApplicationResult.rejected(
        "SLOT_NOT_FOUND", "slot capacity cannot be recalculated without its slot"), now);
    if (!administration.reliable() || !execution.reliable()) {
      ApplicationResult deferred = ApplicationResult.deferred("CAPACITY_PROJECTION_UNRELIABLE",
          "capacity source projections are not reliable", slot.appointmentTimeSlotId());
      ApplicationSupport.releaseDeferred(idempotency, command.metadata(),
          "RecalculateAppointmentCapacityForTimeSlot", deferred);
      return deferred;
    }

    var current = capacity.findBySlotIdForUpdate(command.slotId()).orElse(null);
    long currentVersion = current == null ? 0 : current.version();
    if (currentVersion != command.expectedProjectionVersion()) {
      return terminal(command, ApplicationResult.rejected("VERSION_CONFLICT",
          "capacity projection version changed"), now);
    }

    CapacitySnapshot snapshot = derive(slot);
    long nextVersion = currentVersion + 1;
    var next = new AppointmentCapacityProjectionRepository.CapacityProjection(command.slotId(),
        snapshot.normal(), snapshot.journey(), snapshot.immuno(), nextVersion, now);
    if (current == null) capacity.save(next);
    else if (!capacity.updateIfVersion(next, currentVersion)) {
      return terminal(command, ApplicationResult.rejected("VERSION_CONFLICT",
          "capacity projection changed while recalculating"), now);
    }

    int cancelled = cancelOverflow(command, snapshot, now);
    ApplicationResult result = ApplicationResult.applied(slot.appointmentTimeSlotId(), nextVersion,
        "capacity recalculated; clinic cancellations=" + cancelled);
    ApplicationSupport.complete(idempotency, command.metadata(),
        "RecalculateAppointmentCapacityForTimeSlot", result, now);
    return result;
  }

  private CapacitySnapshot derive(com.estoma.appointments.domain.model.AppointmentTimeSlot slot) {
    var schedule = administration.findSchedule(slot.sourceScheduleId()).orElse(null);
    if (schedule == null || !"ACTIVE".equals(schedule.status())) return CapacitySnapshot.ZERO;
    var calendar = administration.findCalendar(schedule.calendarId()).orElse(null);
    if (calendar == null || !"ACTIVE".equals(calendar.status())) return CapacitySnapshot.ZERO;

    var closures = administration.findClosuresOverlapping(calendar.calendarId(), slot.startsAt(), slot.endsAt());
    if (closures.stream().anyMatch(c -> "SERVICE".equals(c.scope()))) return CapacitySnapshot.ZERO;

    Set<UUID> closedCabins = new HashSet<>();
    Set<UUID> closedTanks = new HashSet<>();
    closures.forEach(c -> {
      if ("CABIN".equals(c.scope()) && c.cabinId() != null) closedCabins.add(c.cabinId());
      if ("TANK".equals(c.scope()) && c.tankId() != null) closedTanks.add(c.tankId());
    });
    execution.findUnavailabilityOverlapping(slot.startsAt(), slot.endsAt()).forEach(u -> {
      if (u.cabinId() != null) closedCabins.add(u.cabinId());
      if (u.tankId() != null) closedTanks.add(u.tankId());
    });
    execution.findCleaningPeriodsOverlapping(slot.startsAt(), slot.endsAt())
        .forEach(cleaning -> closedCabins.add(cleaning.cabinId()));

    Map<UUID, WashAdministrationProjectionRepository.CabinProjection> cabins = new HashMap<>();
    administration.findCabins().stream().filter(c -> "ACTIVE".equals(c.administrativeStatus()))
        .forEach(c -> cabins.put(c.cabinId(), c));

    int normal = 0;
    int journey = 0;
    int immuno = 0;
    for (var tank : administration.findTanks()) {
      if (!"ACTIVE".equals(tank.administrativeStatus()) || closedTanks.contains(tank.tankId())
          || closedCabins.contains(tank.cabinId())) continue;
      var cabin = cabins.get(tank.cabinId());
      if (cabin == null) continue;
      switch (cabin.cabinType()) {
        case "NORMAL" -> normal += tank.configuredCapacity();
        case "JOURNEY" -> journey += tank.configuredCapacity();
        case "IMMUNOCOMPROMISED" -> immuno += tank.configuredCapacity();
        default -> { }
      }
    }
    return new CapacitySnapshot(normal, journey, immuno);
  }

  private int cancelOverflow(Command command, CapacitySnapshot snapshot, Instant now) {
    var all = appointments.findForSlot(command.slotId(), true);
    int sharedDemand = (int) all.stream().filter(Appointment::isActive)
        .filter(a -> a.appointmentType().name().matches("NORMAL|JOURNEY")).count();
    int immunoDemand = (int) all.stream().filter(Appointment::isActive)
        .filter(a -> a.appointmentType().name().equals("IMMUNOCOMPROMISED")).count();
    int sharedCapacity = snapshot.normal() + snapshot.journey();
    int cancelled = 0;

    for (Appointment candidate : all) {
      boolean sharedOverflow = sharedDemand > sharedCapacity
          && candidate.appointmentType().name().matches("NORMAL|JOURNEY");
      boolean immunoOverflow = immunoDemand > snapshot.immuno()
          && candidate.appointmentType().name().equals("IMMUNOCOMPROMISED");
      if ((!sharedOverflow && !immunoOverflow) || !candidate.status().name().equals("SCHEDULED")
          || candidate.entryAuthorizedAt() != null) continue;
      var current = appointments.findByIdForUpdate(candidate.appointmentId()).orElse(candidate);
      if (!current.status().name().equals("SCHEDULED") || current.entryAuthorizedAt() != null) continue;
      var changed = current.cancel(CancellationType.CLINIC, CancellationSubreason.CAPACITY_LOSS,
          "capacity loss", null, now);
      if (!appointments.updateIfVersion(changed, current.version())) continue;
      if (sharedOverflow) sharedDemand--; else immunoDemand--;
      cancelled++;
      ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentCancelled",
          changed.appointmentId(), changed.version(), "{\"appointmentId\":\""
              + changed.appointmentId() + "\",\"cancellationType\":\"CLINIC\","
              + "\"cancellationSubreason\":\"CAPACITY_LOSS\"}", now);
    }
    return cancelled;
  }

  private ApplicationResult terminal(Command command, ApplicationResult result, Instant now) {
    ApplicationSupport.complete(idempotency, command.metadata(),
        "RecalculateAppointmentCapacityForTimeSlot", result, now);
    return result;
  }

  private record CapacitySnapshot(int normal, int journey, int immuno) {
    private static final CapacitySnapshot ZERO = new CapacitySnapshot(0, 0, 0);
  }
}
