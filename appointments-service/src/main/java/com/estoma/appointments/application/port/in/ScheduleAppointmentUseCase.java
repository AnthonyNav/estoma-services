package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Instant;
import java.util.UUID;

public interface ScheduleAppointmentUseCase {
  ApplicationResult schedule(Command command);

  record Command(CommandMetadata metadata, UUID studentAccountId, UUID appointmentTimeSlotId,
      UUID courseSectionId, AppointmentType appointmentType, int instrumentCount,
      PieceType pieceType, UUID exceptionalAuthorizationId, Instant now) {}
}
