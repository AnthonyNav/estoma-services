package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CommandMetadata;
import java.time.Instant;
import java.util.UUID;

public interface RecalculateAppointmentCapacityForTimeSlotUseCase {
  ApplicationResult recalculate(Command command);

  record Command(CommandMetadata metadata, UUID sourceEventId, UUID slotId,
      long expectedProjectionVersion, Instant now) {}
}
