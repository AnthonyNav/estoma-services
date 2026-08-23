package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CommandMetadata;
import java.time.Instant;
import java.util.UUID;

public interface HandleResourceCapacityEventUseCase {
  ApplicationResult handle(Command command);

  record Command(CommandMetadata metadata, UUID sourceEventId, UUID slotId, int normalCapacity,
      int journeyCapacity, int immunocompromisedCapacity, Instant now) {}
}
