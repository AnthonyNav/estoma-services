package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CapacityCausalContext;
import com.estoma.appointments.application.port.in.RecalculateAppointmentCapacityForTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingCapacityEvaluationRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.application.port.out.WashExecutionOperationalProjectionRepository;
import java.time.Duration;
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
public class RecalculateAppointmentCapacityHandler
    implements RecalculateAppointmentCapacityForTimeSlotUseCase {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final AppointmentCapacityProjectionRepository capacity;
  private final WashAdministrationProjectionRepository administration;
  private final WashExecutionOperationalProjectionRepository execution;
  private final PendingCapacityEvaluationRepository pending;
  private final CapacityMatchingCoordinator matching;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;

  public RecalculateAppointmentCapacityHandler(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots, AppointmentCapacityProjectionRepository capacity,
      WashAdministrationProjectionRepository administration,
      WashExecutionOperationalProjectionRepository execution,
      PendingCapacityEvaluationRepository pending, CapacityMatchingCoordinator matching,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox) {
    this.appointments = appointments;
    this.slots = slots;
    this.capacity = capacity;
    this.administration = administration;
    this.execution = execution;
    this.pending = pending;
    this.matching = matching;
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

    CapacityCausalContext causal = command.causalContext();
    com.estoma.appointments.domain.model.Appointment causalAppointment = null;
    if (causal != null && causal.requiresTerminalAppointment()) {
      causalAppointment = appointments.findByIdForUpdate(causal.appointmentId()).orElse(null);
      if (causalAppointment == null) {
        ApplicationResult deferred = ApplicationResult.deferred("CAUSAL_APPOINTMENT_PENDING",
            "cleaning matching waits for its causal appointment", causal.appointmentId());
        ApplicationSupport.releaseDeferred(idempotency, command.metadata(),
            "RecalculateAppointmentCapacityForTimeSlot", deferred);
        return deferred;
      }
      if (causalAppointment.isTerminal()
          && !causal.expectedAppointmentStatus().equals(causalAppointment.status().name())) {
        throw new IllegalStateException("cleaning terminal cause contradicts local Appointment state");
      }
    }

    var slot = slots.findByIdForUpdate(command.slotId()).orElse(null);
    if (slot == null) return terminal(command, ApplicationResult.rejected(
        "SLOT_NOT_FOUND", "slot capacity cannot be recalculated without its slot"), now);
    if (!slot.isActive()) return terminal(command, ApplicationResult.rejected(
        "SLOT_NOT_ACTIVE", "cancelled slots are not recalculated"), now);
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

    CapacitySnapshot previous = current == null ? CapacitySnapshot.ZERO
        : new CapacitySnapshot(current.normalCapacity(), current.journeyCapacity(),
            current.immunocompromisedCapacity());
    CapacitySnapshot snapshot = derive(slot);
    long nextVersion = currentVersion + 1;
    var next = new AppointmentCapacityProjectionRepository.CapacityProjection(command.slotId(),
        snapshot.normal(), snapshot.journey(), snapshot.immuno(), nextVersion, now);
    if (current == null) capacity.save(next);
    else if (!capacity.updateIfVersion(next, currentVersion)) {
      return terminal(command, ApplicationResult.rejected("VERSION_CONFLICT",
          "capacity projection changed while recalculating"), now);
    }

    boolean changed = !previous.equals(snapshot);
    boolean reduced = snapshot.normal() < previous.normal()
        || snapshot.journey() < previous.journey()
        || snapshot.immuno() < previous.immuno();
    if (changed) {
      ApplicationSupport.appendEvent(outbox, command.metadata(),
          "AppointmentTimeSlotCapacityRecalculated", slot.appointmentTimeSlotId(), nextVersion,
          AppointmentEventPayloads.capacityRecalculated(slot.appointmentTimeSlotId(), snapshot.normal(),
              snapshot.journey(), snapshot.immuno(), nextVersion), now);
    }

    boolean deferMatching = causal != null && causal.requiresTerminalAppointment()
        && !causal.expectedAppointmentStatus().equals(causalAppointment.status().name());
    int cancelled = 0;
    if (deferMatching) {
      pending.enqueue(new PendingCapacityEvaluationRepository.PendingEvaluation(
          UUID.randomUUID(), command.sourceEventId(), slot.appointmentTimeSlotId(),
          causal.washExecutionId(), causal.appointmentId(), "CLEANING_STARTED", causal.cleaningTrigger(),
          previous.normal(), snapshot.normal(), previous.journey(), snapshot.journey(),
          previous.immuno(), snapshot.immuno(), nextVersion,
          "PENDING", 0, now, null, null));
    } else {
      cancelled = matching.reconcile(slot.appointmentTimeSlotId(), command.metadata(), now);
      if (reduced) appendCapacityReduced(command, previous, snapshot, nextVersion, cancelled, now);
    }

    ApplicationResult result = ApplicationResult.applied(slot.appointmentTimeSlotId(), nextVersion,
        deferMatching
            ? "capacity recalculated; destructive matching deferred until causal terminal state"
            : "capacity recalculated; clinic cancellations=" + cancelled);
    ApplicationSupport.complete(idempotency, command.metadata(),
        "RecalculateAppointmentCapacityForTimeSlot", result, now);
    return result;
  }

  private void appendCapacityReduced(Command command, CapacitySnapshot previous, CapacitySnapshot current,
      long projectionVersion, int cancelled, Instant now) {
    ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentTimeSlotCapacityReduced",
        command.slotId(), projectionVersion,
        AppointmentEventPayloads.capacityReduced(command.slotId(), previous.normal(), current.normal(),
            previous.journey(), current.journey(), previous.immuno(), current.immuno(), cancelled,
            projectionVersion), now);
  }

  private CapacitySnapshot derive(com.estoma.appointments.domain.model.AppointmentTimeSlot slot) {
    var schedule = administration.findSchedule(slot.sourceScheduleId()).orElse(null);
    if (schedule == null || !"ACTIVE".equals(schedule.status())) return CapacitySnapshot.ZERO;
    if (!schedule.calendarId().equals(slot.calendarId())) return CapacitySnapshot.ZERO;
    var calendar = administration.findCalendar(slot.calendarId()).orElse(null);
    if (calendar == null || !"ACTIVE".equals(calendar.status())) return CapacitySnapshot.ZERO;
    if (slot.serviceDate().isBefore(calendar.effectiveFrom())
        || (calendar.effectiveUntil() != null && slot.serviceDate().isAfter(calendar.effectiveUntil()))) {
      return CapacitySnapshot.ZERO;
    }
    if (slot.serviceDate().getDayOfWeek().getValue() != schedule.dayOfWeek()) return CapacitySnapshot.ZERO;
    var localStart = slot.startsAt().atZone(slot.timezone()).toLocalTime();
    var localEnd = slot.endsAt().atZone(slot.timezone()).toLocalTime();
    if (localStart.isBefore(schedule.openingTime()) || localEnd.isAfter(schedule.closingTime())) {
      return CapacitySnapshot.ZERO;
    }
    if (Duration.between(slot.startsAt(), slot.endsAt()).toMinutes() != schedule.slotDurationMinutes()) {
      return CapacitySnapshot.ZERO;
    }

    var closures = administration.findClosuresOverlapping(slot.calendarId(), slot.startsAt(), slot.endsAt());
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

  private ApplicationResult terminal(Command command, ApplicationResult result, Instant now) {
    ApplicationSupport.complete(idempotency, command.metadata(),
        "RecalculateAppointmentCapacityForTimeSlot", result, now);
    return result;
  }

  private record CapacitySnapshot(int normal, int journey, int immuno) {
    private static final CapacitySnapshot ZERO = new CapacitySnapshot(0, 0, 0);
  }
}
