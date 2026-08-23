package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CommandMetadata;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public interface CreateAppointmentTimeSlotUseCase {
  ApplicationResult create(Command command);

  record Command(CommandMetadata metadata, UUID slotId, UUID sourceScheduleId, LocalDate serviceDate,
      Instant startsAt, Instant endsAt, ZoneId timezone, Instant bookingDeadlineAt,
      Instant cancellationDeadlineAt, Instant arrivalToleranceUntil, Instant now) {}
}
