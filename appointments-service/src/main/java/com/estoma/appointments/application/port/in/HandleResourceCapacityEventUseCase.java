package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CapacityCausalContext;
import com.estoma.appointments.application.model.CommandMetadata;
import java.time.Instant;
import java.util.UUID;

public interface HandleResourceCapacityEventUseCase {
  ApplicationResult handle(Command command);

  /** affectedUntil null means every future active slot from affectedFrom. */
  record Command(CommandMetadata metadata, UUID sourceEventId, Instant affectedFrom,
      Instant affectedUntil, CapacityCausalContext causalContext, Instant now) {
    public Command(CommandMetadata metadata, UUID sourceEventId, Instant affectedFrom,
        Instant affectedUntil, Instant now) {
      this(metadata, sourceEventId, affectedFrom, affectedUntil, null, now);
    }
  }
}
