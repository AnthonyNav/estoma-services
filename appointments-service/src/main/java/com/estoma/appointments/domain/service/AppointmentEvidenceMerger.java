package com.estoma.appointments.domain.service;

import com.estoma.appointments.domain.error.DomainViolationException;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentStatus;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.Objects;

/** Adds late operational evidence without reopening or otherwise changing Appointment state. */
public final class AppointmentEvidenceMerger {
  private AppointmentEvidenceMerger() {}

  public static Appointment addEntryAuthorization(Appointment current, Instant decidedAt) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(decidedAt, "decidedAt");
    if (current.entryAuthorizedAt() != null) return current;
    if (current.status() == AppointmentStatus.MISSED || current.status() == AppointmentStatus.ENTRY_REJECTED) {
      throw new DomainViolationException("entry authorization conflicts with terminal appointment state");
    }
    if (current.status() == AppointmentStatus.CANCELLED
        && current.cancellationType() == CancellationType.STUDENT) {
      throw new DomainViolationException("student-cancelled appointment cannot gain entry authorization");
    }
    return Appointment.reconstitute(current.appointmentId(), current.appointmentTimeSlotId(),
        current.studentAccountId(), current.courseSectionId(), current.appointmentType(),
        current.instrumentCount(), current.pieceType(), current.status(), decidedAt,
        current.exceptionalAuthorizationId(), current.cancellationType(),
        current.cancellationSubreason(), current.cancellationReason(), current.cancelledByAccountId(),
        current.cancelledAt(), current.rejectionReason(), current.rejectedByAccountId(),
        current.rejectedAt(), current.missedAt(), current.startedAt(), current.completedByAccountId(),
        current.completedAt(), current.createdAt(), decidedAt, current.version() + 1);
  }
}
