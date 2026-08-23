package com.estoma.appointments.domain.model;

import com.estoma.appointments.domain.error.DomainViolationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Appointment aggregate. Every mutation returns a new version; terminal states never reopen. */
public final class Appointment {
  private final UUID appointmentId;
  private final UUID appointmentTimeSlotId;
  private final UUID studentAccountId;
  private final UUID courseSectionId;
  private final AppointmentType appointmentType;
  private final int instrumentCount;
  private final PieceType pieceType;
  private final AppointmentStatus status;
  private final Instant entryAuthorizedAt;
  private final UUID exceptionalAuthorizationId;
  private final CancellationType cancellationType;
  private final CancellationSubreason cancellationSubreason;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final long version;

  private Appointment(UUID id, UUID slotId, UUID studentId, UUID courseSectionId, AppointmentType type,
      int instrumentCount, PieceType pieceType, AppointmentStatus status, Instant entryAuthorizedAt,
      UUID exceptionalAuthorizationId, CancellationType cancellationType,
      CancellationSubreason cancellationSubreason, Instant createdAt, Instant updatedAt, long version) {
    this.appointmentId = Objects.requireNonNull(id, "appointmentId");
    this.appointmentTimeSlotId = Objects.requireNonNull(slotId, "appointmentTimeSlotId");
    this.studentAccountId = Objects.requireNonNull(studentId, "studentAccountId");
    this.courseSectionId = courseSectionId;
    this.appointmentType = Objects.requireNonNull(type, "appointmentType");
    validateInstrumentCount(type, instrumentCount);
    this.instrumentCount = instrumentCount;
    this.pieceType = Objects.requireNonNull(pieceType, "pieceType");
    this.status = Objects.requireNonNull(status, "status");
    this.entryAuthorizedAt = entryAuthorizedAt;
    this.exceptionalAuthorizationId = exceptionalAuthorizationId;
    this.cancellationType = cancellationType;
    this.cancellationSubreason = cancellationSubreason;
    validateCancellation(status, cancellationType, cancellationSubreason);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (version <= 0) throw new DomainViolationException("appointment version must be positive");
    this.version = version;
  }

  public static Appointment scheduled(UUID id, UUID slotId, UUID studentId, UUID courseSectionId,
      AppointmentType type, int instrumentCount, PieceType pieceType, UUID exceptionalAuthorizationId,
      Instant now) {
    return new Appointment(id, slotId, studentId, courseSectionId, type, instrumentCount, pieceType,
        AppointmentStatus.SCHEDULED, null, exceptionalAuthorizationId, null, null, now, now, 1);
  }

  public static Appointment create(UUID id, UUID slotId, UUID studentId, UUID courseSectionId,
      AppointmentType type, int instrumentCount, PieceType pieceType, UUID exceptionalAuthorizationId,
      Instant now) {
    return scheduled(id, slotId, studentId, courseSectionId, type, instrumentCount, pieceType,
        exceptionalAuthorizationId, now);
  }

  public static Appointment scheduled(UUID id, UUID slotId, UUID studentId, AppointmentType type,
      int instrumentCount, PieceType pieceType, UUID exceptionalAuthorizationId, Instant now) {
    return scheduled(id, slotId, studentId, null, type, instrumentCount, pieceType, exceptionalAuthorizationId, now);
  }

  public static Appointment reconstitute(UUID id, UUID slotId, UUID studentId, UUID courseSectionId,
      AppointmentType type, int instrumentCount, PieceType pieceType, AppointmentStatus status,
      Instant entryAuthorizedAt, UUID exceptionalAuthorizationId, CancellationType cancellationType,
      CancellationSubreason cancellationSubreason, Instant createdAt, Instant updatedAt, long version) {
    return new Appointment(id, slotId, studentId, courseSectionId, type, instrumentCount, pieceType, status,
        entryAuthorizedAt, exceptionalAuthorizationId, cancellationType, cancellationSubreason,
        createdAt, updatedAt, version);
  }

  public Appointment authorizeEntry(Instant decidedAt) {
    Objects.requireNonNull(decidedAt, "decidedAt");
    if (status.isTerminal()) throw new DomainViolationException("terminal appointment cannot authorize entry");
    if (entryAuthorizedAt != null) return this;
    return copy(status, decidedAt, cancellationType, cancellationSubreason, decidedAt, version + 1);
  }

  public Appointment start(Instant at) {
    ensureStatus(AppointmentStatus.SCHEDULED, "start");
    return copy(AppointmentStatus.IN_PROGRESS, entryAuthorizedAt, null, null, at, version + 1);
  }

  public Appointment complete(Instant at) {
    ensureStatus(AppointmentStatus.IN_PROGRESS, "complete");
    return copy(AppointmentStatus.COMPLETED, entryAuthorizedAt, null, null, at, version + 1);
  }

  public Appointment rejectEntry(Instant at) {
    ensureStatus(AppointmentStatus.SCHEDULED, "reject entry");
    return copy(AppointmentStatus.ENTRY_REJECTED, entryAuthorizedAt, null, null, at, version + 1);
  }

  public Appointment markMissed(Instant at) {
    ensureStatus(AppointmentStatus.SCHEDULED, "mark missed");
    if (entryAuthorizedAt != null) throw new DomainViolationException("entry-authorized appointment cannot be missed");
    return copy(AppointmentStatus.MISSED, entryAuthorizedAt, null, null, at, version + 1);
  }

  public Appointment cancel(CancellationType type, CancellationSubreason subreason, Instant at) {
    Objects.requireNonNull(type, "cancellationType");
    Objects.requireNonNull(at, "cancelledAt");
    if (status.isTerminal()) throw new DomainViolationException("terminal appointment cannot be cancelled");
    if (type == CancellationType.STUDENT && status != AppointmentStatus.SCHEDULED) {
      throw new DomainViolationException("student cancellation is only valid for SCHEDULED appointments");
    }
    validateCancellation(AppointmentStatus.CANCELLED, type, subreason);
    if (type == CancellationType.STUDENT && entryAuthorizedAt != null) {
      throw new DomainViolationException("entry-authorized appointment cannot be cancelled by student");
    }
    return copy(AppointmentStatus.CANCELLED, entryAuthorizedAt, type, subreason, at, version + 1);
  }

  public Appointment cancelByStudent(Instant at) { return cancel(CancellationType.STUDENT, null, at); }
  public Appointment cancelByClinic(CancellationSubreason subreason, Instant at) { return cancel(CancellationType.CLINIC, subreason, at); }

  public int points() {
    if (status == AppointmentStatus.MISSED) return 1;
    if (status == AppointmentStatus.CANCELLED && cancellationType == CancellationType.STUDENT) return 1;
    return 0;
  }

  public boolean consumesExceptionalAuthorization() {
    if (exceptionalAuthorizationId == null) return false;
    return !(status == AppointmentStatus.CANCELLED && cancellationType == CancellationType.CLINIC);
  }

  public boolean isActive() { return status.reservesCapacity(); }
  public boolean isTerminal() { return status.isTerminal(); }

  private Appointment copy(AppointmentStatus next, Instant entryAt, CancellationType cType,
      CancellationSubreason subreason, Instant now, long nextVersion) {
    return new Appointment(appointmentId, appointmentTimeSlotId, studentAccountId, courseSectionId,
        appointmentType, instrumentCount, pieceType, next, entryAt, exceptionalAuthorizationId,
        cType, subreason, createdAt, now, nextVersion);
  }

  private void ensureStatus(AppointmentStatus expected, String operation) {
    if (status != expected) throw new DomainViolationException("cannot " + operation + " from " + status);
  }

  public static void validateInstrumentCount(AppointmentType type, int count) {
    int max = type == AppointmentType.JOURNEY ? 80 : 40;
    if (count < 1 || count > max) {
      throw new DomainViolationException(type + " instrumentCount must be between 1 and " + max);
    }
  }

  public static void validateCancellation(AppointmentStatus status, CancellationType type,
      CancellationSubreason subreason) {
    if (status != AppointmentStatus.CANCELLED && (type != null || subreason != null)) {
      throw new DomainViolationException("only CANCELLED appointments may carry cancellation metadata");
    }
    if (status == AppointmentStatus.CANCELLED && type == null) {
      throw new DomainViolationException("cancelled appointment requires cancellationType");
    }
    if (type == CancellationType.CLINIC && subreason == null) {
      throw new DomainViolationException("clinic cancellation requires cancellationSubreason");
    }
    if (type == CancellationType.STUDENT && subreason != null) {
      throw new DomainViolationException("student cancellation cannot have a clinic subreason");
    }
    if (type == null && subreason != null) {
      throw new DomainViolationException("cancellationSubreason requires cancellationType");
    }
  }

  public UUID appointmentId() { return appointmentId; }
  public UUID appointmentTimeSlotId() { return appointmentTimeSlotId; }
  public UUID studentAccountId() { return studentAccountId; }
  public UUID courseSectionId() { return courseSectionId; }
  public AppointmentType appointmentType() { return appointmentType; }
  public int instrumentCount() { return instrumentCount; }
  public PieceType pieceType() { return pieceType; }
  public AppointmentStatus status() { return status; }
  public Instant entryAuthorizedAt() { return entryAuthorizedAt; }
  public UUID exceptionalAuthorizationId() { return exceptionalAuthorizationId; }
  public CancellationType cancellationType() { return cancellationType; }
  public CancellationSubreason cancellationSubreason() { return cancellationSubreason; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
  public long version() { return version; }
}
