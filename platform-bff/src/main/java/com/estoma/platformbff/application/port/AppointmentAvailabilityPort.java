package com.estoma.platformbff.application.port;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable cache-aside boundary for Appointments-owned availability snapshots. */
public interface AppointmentAvailabilityPort {
  Optional<Snapshot> findSnapshot(Context context);

  /** Claims a refresh slot only when the prior request for this exact context is old enough. */
  boolean enqueueRefresh(Refresh refresh, Instant replaceBefore);

  Optional<Refresh> findRefreshByMessageId(UUID messageId);

  Optional<Refresh> findRefresh(Context context);

  Optional<Refresh> claimNext(Instant now);

  boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt);

  boolean markFailed(UUID messageId, UUID claimToken, String errorCode, Instant nextAttemptAt);

  void applySnapshot(Refresh expectedRefresh, UUID resultMessageId, Snapshot snapshot, Instant appliedAt);

  void rejectRefresh(Refresh expectedRefresh, UUID resultMessageId, String errorCode, Instant appliedAt);

  record Context(
      UUID studentAccountId,
      LocalDate serviceDate,
      String appointmentType,
      int instrumentCount,
      String pieceType,
      UUID courseSectionId) {
    public Context {
      if (studentAccountId == null || serviceDate == null || appointmentType == null
          || appointmentType.isBlank() || instrumentCount <= 0 || pieceType == null
          || pieceType.isBlank() || courseSectionId == null) {
        throw new IllegalArgumentException("availability context is incomplete");
      }
    }

    public UUID contextId() {
      String canonical = "appointment-availability-v1|" + studentAccountId + "|" + serviceDate
          + "|" + appointmentType + "|" + instrumentCount + "|" + pieceType + "|"
          + courseSectionId;
      return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }
  }

  record Refresh(
      Context context,
      UUID messageId,
      UUID correlationId,
      String status,
      UUID claimToken,
      Instant claimUntil,
      int attemptCount,
      Instant nextAttemptAt,
      Instant publishedAt,
      String lastErrorCode,
      Instant requestedAt) {
    public Refresh {
      if (context == null || messageId == null || correlationId == null || status == null
          || status.isBlank() || attemptCount < 0 || requestedAt == null) {
        throw new IllegalArgumentException("invalid availability refresh");
      }
    }

    public static Refresh pending(Context context, Instant now) {
      return new Refresh(context, UUID.randomUUID(), UUID.randomUUID(), "PENDING", null, null,
          0, null, null, null, now);
    }
  }

  record Snapshot(
      Context requestContext,
      UUID snapshotId,
      Instant snapshotGeneratedAt,
      Instant freshUntil,
      boolean canSchedule,
      List<String> blockingReasons,
      int dailyPenaltyPoints,
      int dailyCompletedAppointments,
      boolean exceptionalAuthorizationRequired,
      boolean exceptionalAuthorizationAvailable,
      UUID exceptionalAuthorizationId,
      List<AvailableTimeSlot> availableTimeSlots) {
    public Snapshot {
      blockingReasons = List.copyOf(blockingReasons);
      availableTimeSlots = List.copyOf(availableTimeSlots);
      if (requestContext == null || snapshotId == null || snapshotGeneratedAt == null
          || freshUntil == null || freshUntil.isBefore(snapshotGeneratedAt)
          || dailyPenaltyPoints < 0 || dailyCompletedAppointments < 0
          || canSchedule != blockingReasons.isEmpty()
          || exceptionalAuthorizationAvailable != (exceptionalAuthorizationId != null)
          || exceptionalAuthorizationAvailable && !exceptionalAuthorizationRequired
          || exceptionalAuthorizationRequired && !exceptionalAuthorizationAvailable
              && !blockingReasons.contains("EXCEPTIONAL_AUTHORIZATION_REQUIRED")) {
        throw new IllegalArgumentException("invalid availability snapshot");
      }
    }
  }

  record AvailableTimeSlot(
      UUID appointmentTimeSlotId,
      Instant startsAt,
      Instant endsAt,
      int availableCapacity,
      Instant bookingDeadlineAt) {
    public AvailableTimeSlot {
      if (appointmentTimeSlotId == null || startsAt == null || endsAt == null
          || !endsAt.isAfter(startsAt) || availableCapacity <= 0 || bookingDeadlineAt == null
          || bookingDeadlineAt.isAfter(startsAt)) {
        throw new IllegalArgumentException("invalid availability time slot");
      }
    }
  }
}
