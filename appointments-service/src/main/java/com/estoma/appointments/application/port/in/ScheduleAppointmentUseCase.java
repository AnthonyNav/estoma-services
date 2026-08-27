package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Instant;
import java.util.UUID;

public interface ScheduleAppointmentUseCase {
  ApplicationResult schedule(Command command);

  record Command(CommandMetadata metadata, UUID appointmentId, UUID studentAccountId,
      UUID appointmentTimeSlotId, UUID courseSectionId, AppointmentType appointmentType,
      int instrumentCount, PieceType pieceType, UUID exceptionalAuthorizationId,
      boolean regulationAccepted, Instant now) {

    /** Compatibility constructor for existing direct application callers. */
    public Command(CommandMetadata metadata, UUID appointmentId, UUID studentAccountId,
        UUID appointmentTimeSlotId, UUID courseSectionId, AppointmentType appointmentType,
        int instrumentCount, PieceType pieceType, UUID exceptionalAuthorizationId, Instant now) {
      this(metadata, appointmentId, studentAccountId, appointmentTimeSlotId, courseSectionId,
          appointmentType, instrumentCount, pieceType, exceptionalAuthorizationId, true, now);
    }

    /** Compatibility constructor for direct pre-wire application tests. */
    public Command(CommandMetadata metadata, UUID studentAccountId, UUID appointmentTimeSlotId,
        UUID courseSectionId, AppointmentType appointmentType, int instrumentCount,
        PieceType pieceType, UUID exceptionalAuthorizationId, Instant now) {
      this(metadata, UUID.randomUUID(), studentAccountId, appointmentTimeSlotId, courseSectionId,
          appointmentType, instrumentCount, pieceType, exceptionalAuthorizationId, true, now);
    }
  }
}
