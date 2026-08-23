package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CommandMetadata;
import java.time.Instant;
import java.util.UUID;

public interface MarkAppointmentAsMissedUseCase {
  ApplicationResult mark(Command command);

  record Command(CommandMetadata metadata, UUID appointmentId, long expectedVersion, Instant now) {}
}
