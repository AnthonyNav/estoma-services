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
  private final String cancellationReason;
  private final UUID cancelledByAccountId;
  private final Instant cancelledAt;
  private final String rejectionReason;
  private final UUID rejectedByAccountId;
  private final Instant rejectedAt;
  private final Instant missedAt;
  private final Instant startedAt;
  private final UUID completedByAccountId;
  private final Instant completedAt;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final long version;

  private Appointment(UUID id, UUID slotId, UUID studentId, UUID courseSectionId, AppointmentType type,
      int instrumentCount, PieceType pieceType, AppointmentStatus status, Instant entryAuthorizedAt,
      UUID exceptionalAuthorizationId, CancellationType cancellationType,
      CancellationSubreason cancellationSubreason, String cancellationReason, UUID cancelledByAccountId,
      Instant cancelledAt, String rejectionReason, UUID rejectedByAccountId, Instant rejectedAt,
      Instant missedAt, Instant startedAt, UUID completedByAccountId, Instant completedAt,
      Instant createdAt, Instant updatedAt, long version) {
    this.appointmentId = Objects.requireNonNull(id, "appointmentId");
    this.appointmentTimeSlotId = Objects.requireNonNull(slotId, "appointmentTimeSlotId");
    this.studentAccountId = Objects.requireNonNull(studentId, "studentAccountId");
    this.courseSectionId = Objects.requireNonNull(courseSectionId, "courseSectionId");
    this.appointmentType = Objects.requireNonNull(type, "appointmentType");
    validateInstrumentCount(type, instrumentCount);
    this.instrumentCount = instrumentCount;
    this.pieceType = Objects.requireNonNull(pieceType, "pieceType");
    this.status = Objects.requireNonNull(status, "status");
    this.entryAuthorizedAt = entryAuthorizedAt;
    this.exceptionalAuthorizationId = exceptionalAuthorizationId;
    this.cancellationType = cancellationType;
    this.cancellationSubreason = cancellationSubreason;
    this.cancellationReason = cancellationReason;
    this.cancelledByAccountId = cancelledByAccountId;
    this.cancelledAt = cancelledAt;
    this.rejectionReason = rejectionReason;
    this.rejectedByAccountId = rejectedByAccountId;
    this.rejectedAt = rejectedAt;
    this.missedAt = missedAt;
    this.startedAt = startedAt;
    this.completedByAccountId = completedByAccountId;
    this.completedAt = completedAt;
    validateStateMetadata();
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (version <= 0) throw new DomainViolationException("appointment version must be positive");
    this.version = version;
  }

  public static Appointment scheduled(UUID id, UUID slotId, UUID studentId, UUID courseSectionId,
      AppointmentType type, int instrumentCount, PieceType pieceType, UUID exceptionalAuthorizationId,
      Instant now) {
    Objects.requireNonNull(now, "now");
    return new Appointment(id, slotId, studentId, courseSectionId, type, instrumentCount, pieceType,
        AppointmentStatus.SCHEDULED, null, exceptionalAuthorizationId, null, null, null, null, null,
        null, null, null, null, null, null, null, now, now, 1);
  }

  public static Appointment create(UUID id, UUID slotId, UUID studentId, UUID courseSectionId,
      AppointmentType type, int instrumentCount, PieceType pieceType, UUID exceptionalAuthorizationId,
      Instant now) {
    return scheduled(id, slotId, studentId, courseSectionId, type, instrumentCount, pieceType,
        exceptionalAuthorizationId, now);
  }

  public static Appointment reconstitute(UUID id, UUID slotId, UUID studentId, UUID courseSectionId,
      AppointmentType type, int instrumentCount, PieceType pieceType, AppointmentStatus status,
      Instant entryAuthorizedAt, UUID exceptionalAuthorizationId, CancellationType cancellationType,
      CancellationSubreason cancellationSubreason, String cancellationReason, UUID cancelledByAccountId,
      Instant cancelledAt, String rejectionReason, UUID rejectedByAccountId, Instant rejectedAt,
      Instant missedAt, Instant startedAt, UUID completedByAccountId, Instant completedAt,
      Instant createdAt, Instant updatedAt, long version) {
    return new Appointment(id, slotId, studentId, courseSectionId, type, instrumentCount, pieceType,
        status, entryAuthorizedAt, exceptionalAuthorizationId, cancellationType, cancellationSubreason,
        cancellationReason, cancelledByAccountId, cancelledAt, rejectionReason, rejectedByAccountId,
        rejectedAt, missedAt, startedAt, completedByAccountId, completedAt, createdAt, updatedAt, version);
  }

  public Appointment authorizeEntry(Instant decidedAt) {
    Objects.requireNonNull(decidedAt, "decidedAt");
    if (status.isTerminal()) throw new DomainViolationException("terminal appointment cannot authorize entry");
    if (entryAuthorizedAt != null) return this;
    return copy(status, decidedAt, cancellationType, cancellationSubreason, cancellationReason,
        cancelledByAccountId, cancelledAt, rejectionReason, rejectedByAccountId, rejectedAt, missedAt,
        startedAt, completedByAccountId, completedAt, decidedAt, version + 1);
  }

  public Appointment start(Instant at) {
    ensureStatus(AppointmentStatus.SCHEDULED, "start");
    Objects.requireNonNull(at, "startedAt");
    return copy(AppointmentStatus.IN_PROGRESS, entryAuthorizedAt, null, null, null, null, null,
        null, null, null, null, at, null, null, at, version + 1);
  }

  public Appointment complete(UUID completedByAccountId, Instant at) {
    ensureStatus(AppointmentStatus.IN_PROGRESS, "complete");
    Objects.requireNonNull(at, "completedAt");
    return copy(AppointmentStatus.COMPLETED, entryAuthorizedAt, null, null, null, null, null,
        null, null, null, null, startedAt, completedByAccountId, at, at, version + 1);
  }

  public Appointment complete(Instant at) { return complete(null, at); }

  public Appointment rejectEntry(String reason, UUID rejectedByAccountId, Instant at) {
    ensureStatus(AppointmentStatus.SCHEDULED, "reject entry");
    if (reason == null || reason.isBlank()) throw new DomainViolationException("rejectionReason is required");
    Objects.requireNonNull(at, "rejectedAt");
    return copy(AppointmentStatus.ENTRY_REJECTED, entryAuthorizedAt, null, null, null, null, null,
        reason, rejectedByAccountId, at, null, null, null, null, at, version + 1);
  }

  public Appointment markMissed(Instant at) {
    ensureStatus(AppointmentStatus.SCHEDULED, "mark missed");
    Objects.requireNonNull(at, "missedAt");
    if (entryAuthorizedAt != null) throw new DomainViolationException("entry-authorized appointment cannot be missed");
    return copy(AppointmentStatus.MISSED, entryAuthorizedAt, null, null, null, null, null,
        null, null, null, at, null, null, null, at, version + 1);
  }

  public Appointment cancel(CancellationType type, CancellationSubreason subreason, String reason,
      UUID cancelledByAccountId, Instant at) {
    Objects.requireNonNull(type, "cancellationType");
    Objects.requireNonNull(at, "cancelledAt");
    if (status.isTerminal()) throw new DomainViolationException("terminal appointment cannot be cancelled");
    if (type == CancellationType.STUDENT && status != AppointmentStatus.SCHEDULED) {
      throw new DomainViolationException("student cancellation is only valid for SCHEDULED appointments");
    }
    validateCancellation(type, subreason);
    if (type == CancellationType.STUDENT && entryAuthorizedAt != null) {
      throw new DomainViolationException("entry-authorized appointment cannot be cancelled by student");
    }
    return copy(AppointmentStatus.CANCELLED, entryAuthorizedAt, type, subreason, reason,
        cancelledByAccountId, at, null, null, null, null, startedAt, null, null, at, version + 1);
  }

  public Appointment cancel(CancellationType type, CancellationSubreason subreason, Instant at) {
    return cancel(type, subreason, null, null, at);
  }

  public Appointment cancelByStudent(Instant at) { return cancel(CancellationType.STUDENT, null, at); }
  public Appointment cancelByClinic(CancellationSubreason subreason, Instant at) {
    return cancel(CancellationType.CLINIC, subreason, at);
  }

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
      CancellationSubreason subreason, String cancelReason, UUID cancelledBy, Instant cancelAt,
      String rejectReason, UUID rejectedBy, Instant rejectAt, Instant missedAt, Instant startedAt,
      UUID completedBy, Instant completedAt, Instant now, long nextVersion) {
    return new Appointment(appointmentId, appointmentTimeSlotId, studentAccountId, courseSectionId,
        appointmentType, instrumentCount, pieceType, next, entryAt, exceptionalAuthorizationId,
        cType, subreason, cancelReason, cancelledBy, cancelAt, rejectReason, rejectedBy, rejectAt,
        missedAt, startedAt, completedBy, completedAt, createdAt, now, nextVersion);
  }

  private void ensureStatus(AppointmentStatus expected, String operation) {
    if (status != expected) throw new DomainViolationException("cannot " + operation + " from " + status);
  }

  private void validateStateMetadata() {
    validateCancellation(cancellationType, cancellationSubreason);
    if (status == AppointmentStatus.CANCELLED && cancelledAt == null) {
      throw new DomainViolationException("cancelled appointment requires cancelledAt");
    }
    if (status != AppointmentStatus.CANCELLED
        && (cancellationType != null || cancellationSubreason != null || cancellationReason != null
            || cancelledByAccountId != null || cancelledAt != null)) {
      throw new DomainViolationException("only CANCELLED appointments may carry cancellation metadata");
    }
    if (status == AppointmentStatus.ENTRY_REJECTED
        && (rejectionReason == null || rejectionReason.isBlank() || rejectedAt == null)) {
      throw new DomainViolationException("entry rejected appointment requires rejection metadata");
    }
    if (status != AppointmentStatus.ENTRY_REJECTED
        && (rejectionReason != null || rejectedByAccountId != null || rejectedAt != null)) {
      throw new DomainViolationException("only ENTRY_REJECTED appointments may carry rejection metadata");
    }
    if ((status == AppointmentStatus.MISSED) != (missedAt != null)) {
      throw new DomainViolationException("MISSED status and missedAt must agree");
    }
    if ((status == AppointmentStatus.IN_PROGRESS || status == AppointmentStatus.COMPLETED)
        && startedAt == null) {
      throw new DomainViolationException("started appointment requires startedAt");
    }
    if (status == AppointmentStatus.COMPLETED && completedAt == null) {
      throw new DomainViolationException("completed appointment requires completedAt");
    }
    if (status != AppointmentStatus.COMPLETED && (completedByAccountId != null || completedAt != null)) {
      throw new DomainViolationException("only COMPLETED appointments may carry completion metadata");
    }
  }

  public static void validateInstrumentCount(AppointmentType type, int count) {
    int max = type == AppointmentType.JOURNEY ? 80 : 40;
    if (count < 1 || count > max) {
      throw new DomainViolationException(type + " instrumentCount must be between 1 and " + max);
    }
  }

  private static void validateCancellation(CancellationType type, CancellationSubreason subreason) {
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
  public String cancellationReason() { return cancellationReason; }
  public UUID cancelledByAccountId() { return cancelledByAccountId; }
  public Instant cancelledAt() { return cancelledAt; }
  public String rejectionReason() { return rejectionReason; }
  public UUID rejectedByAccountId() { return rejectedByAccountId; }
  public Instant rejectedAt() { return rejectedAt; }
  public Instant missedAt() { return missedAt; }
  public Instant startedAt() { return startedAt; }
  public UUID completedByAccountId() { return completedByAccountId; }
  public Instant completedAt() { return completedAt; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
  public long version() { return version; }
}
