package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CapacityCausalContext;
import com.estoma.appointments.application.model.OutboxIntent;
import com.estoma.appointments.application.port.in.HandleResourceCapacityEventUseCase;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns one source-capacity fact into one durable internal recalculation command per affected slot. */
@Service
public class HandleResourceCapacityEventHandler implements HandleResourceCapacityEventUseCase {
  private final AppointmentTimeSlotRepository slots;
  private final AppointmentCapacityProjectionRepository capacity;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;

  public HandleResourceCapacityEventHandler(AppointmentTimeSlotRepository slots,
      AppointmentCapacityProjectionRepository capacity, CommandIdempotencyRepository idempotency,
      OutboxRepository outbox) {
    this.slots = slots;
    this.capacity = capacity;
    this.idempotency = idempotency;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public ApplicationResult handle(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "HandleResourceCapacityEvent", ApplicationSupport.fingerprint(command.sourceEventId(),
            command.affectedFrom(), command.affectedUntil(), command.causalContext()), now);
    if (gate.isPresent()) return gate.get();

    Instant from = command.affectedFrom() == null ? now : command.affectedFrom();
    List<com.estoma.appointments.domain.model.AppointmentTimeSlot> affected =
        command.affectedUntil() == null
            ? slots.findActiveFrom(from)
            : slots.findActiveOverlapping(from, command.affectedUntil());

    for (var slot : affected) {
      long expectedVersion = capacity.findBySlotId(slot.appointmentTimeSlotId())
          .map(AppointmentCapacityProjectionRepository.CapacityProjection::version).orElse(0L);
      String payload = recalculationPayload(command.sourceEventId(), slot.appointmentTimeSlotId(),
          expectedVersion, command.causalContext());
      ApplicationSupport.append(outbox, new OutboxIntent(
          "RecalculateAppointmentCapacityForTimeSlot", "COMMAND", slot.appointmentTimeSlotId(),
          expectedVersion, command.metadata().correlationId(), command.sourceEventId(), payload, now));
    }

    ApplicationResult result = ApplicationResult.applied(null, 0,
        "capacity change scheduled recalculation for " + affected.size() + " slots");
    ApplicationSupport.complete(idempotency, command.metadata(), "HandleResourceCapacityEvent", result, now);
    return result;
  }

  private static String recalculationPayload(java.util.UUID sourceEventId, java.util.UUID slotId,
      long expectedVersion, CapacityCausalContext context) {
    StringBuilder payload = new StringBuilder("{\"sourceEventId\":\"").append(sourceEventId)
        .append("\",\"slotId\":\"").append(slotId)
        .append("\",\"expectedProjectionVersion\":").append(expectedVersion);
    if (context != null) {
      payload.append(",\"causalContext\":{\"washExecutionId\":\"")
          .append(context.washExecutionId())
          .append("\",\"appointmentId\":\"").append(context.appointmentId())
          .append("\",\"cleaningTrigger\":\"").append(context.cleaningTrigger()).append("\"}");
    }
    return payload.append('}').toString();
  }
}
