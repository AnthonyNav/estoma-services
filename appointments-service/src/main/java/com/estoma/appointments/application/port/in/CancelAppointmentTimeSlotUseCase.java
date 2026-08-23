package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CommandMetadata;
import java.time.Instant;
import java.util.UUID;

public interface CancelAppointmentTimeSlotUseCase {
  ApplicationResult cancel(Command command);

  record Command(CommandMetadata metadata, UUID slotId, long expectedVersion, Instant now) {}
}
