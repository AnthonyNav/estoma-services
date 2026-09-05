package com.estoma.appointments.application.port.in;

import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.PieceType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Owner-derived availability snapshot used by the BFF read model. */
public interface GetAppointmentAvailabilityUseCase {
  Snapshot get(Request request, Instant now);

  record Request(UUID studentAccountId, LocalDate serviceDate, AppointmentType appointmentType,
      int instrumentCount, PieceType pieceType, UUID courseSectionId) {
    public Request {
      if (studentAccountId == null || serviceDate == null || appointmentType == null
          || pieceType == null || courseSectionId == null) {
        throw new IllegalArgumentException("availability request context is incomplete");
      }
      com.estoma.appointments.domain.model.Appointment.validateInstrumentCount(
          appointmentType, instrumentCount);
    }
  }

  record Snapshot(Request requestContext, boolean canSchedule, List<String> blockingReasons,
      int dailyPenaltyPoints, int dailyCompletedAppointments,
      boolean exceptionalAuthorizationRequired, boolean exceptionalAuthorizationAvailable,
      UUID exceptionalAuthorizationId, List<AvailableTimeSlot> availableTimeSlots,
      Instant snapshotGeneratedAt, Instant freshUntil) {
    public Snapshot {
      blockingReasons = List.copyOf(blockingReasons);
      availableTimeSlots = List.copyOf(availableTimeSlots);
      if (requestContext == null || snapshotGeneratedAt == null || freshUntil == null
          || freshUntil.isBefore(snapshotGeneratedAt) || dailyPenaltyPoints < 0
          || dailyCompletedAppointments < 0) {
        throw new IllegalArgumentException("invalid availability snapshot");
      }
      if (exceptionalAuthorizationAvailable != (exceptionalAuthorizationId != null)) {
        throw new IllegalArgumentException("available authorization and identifier must agree");
      }
      if (canSchedule != blockingReasons.isEmpty()) {
        throw new IllegalArgumentException("canSchedule and blockingReasons must agree");
      }
    }
  }

  record AvailableTimeSlot(UUID appointmentTimeSlotId, Instant startsAt, Instant endsAt,
      int availableCapacity, Instant bookingDeadlineAt) {
    public AvailableTimeSlot {
      if (appointmentTimeSlotId == null || startsAt == null || endsAt == null
          || bookingDeadlineAt == null || !endsAt.isAfter(startsAt) || availableCapacity <= 0) {
        throw new IllegalArgumentException("invalid available time slot");
      }
    }
  }
}
