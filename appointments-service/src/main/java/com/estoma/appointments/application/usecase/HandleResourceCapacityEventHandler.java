package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.HandleResourceCapacityEventUseCase;
import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class HandleResourceCapacityEventHandler implements HandleResourceCapacityEventUseCase {
  private final AppointmentCapacityProjectionRepository capacity;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;

  public HandleResourceCapacityEventHandler(AppointmentCapacityProjectionRepository capacity,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox) {
    this.capacity = capacity;
    this.idempotency = idempotency;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public ApplicationResult handle(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "HandleResourceCapacityEvent", ApplicationSupport.fingerprint(command.sourceEventId(), command.slotId(),
            command.normalCapacity(), command.journeyCapacity(), command.immunocompromisedCapacity()), now);
    if (gate.isPresent()) return gate.get();
    var current = capacity.findBySlotIdForUpdate(command.slotId()).orElse(null);
    long nextVersion = current == null ? 1 : current.version() + 1;
    var projection = new AppointmentCapacityProjectionRepository.CapacityProjection(command.slotId(),
        command.normalCapacity(), command.journeyCapacity(), command.immunocompromisedCapacity(), nextVersion, now);
    if (current == null) capacity.save(projection);
    else if (!capacity.updateIfVersion(projection, current.version())) {
      ApplicationResult conflict = ApplicationResult.rejected("CONCURRENT_MODIFICATION", "capacity changed while applying event");
      ApplicationSupport.appendResult(outbox, command.metadata(), conflict, now);
      ApplicationSupport.complete(idempotency, command.metadata(), "HandleResourceCapacityEvent", conflict, now);
      return conflict;
    }
    ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentCapacityRecalculationRequested",
        command.slotId(), nextVersion, "{\"slotId\":\"" + command.slotId() + "\",\"sourceEventId\":\""
            + command.sourceEventId() + "\"}", now);
    ApplicationResult result = ApplicationResult.applied(command.slotId(), nextVersion, "capacity projection updated");
    ApplicationSupport.appendResult(outbox, command.metadata(), result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "HandleResourceCapacityEvent", result, now);
    return result;
  }
}
