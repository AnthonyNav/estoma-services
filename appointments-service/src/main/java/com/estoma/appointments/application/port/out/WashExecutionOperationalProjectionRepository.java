package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface WashExecutionOperationalProjectionRepository {
  void upsertResourceUnavailability(ResourceUnavailabilityProjection projection);
  void upsertCleaningPeriod(CleaningPeriodProjection projection);
  boolean reliable();

  record ResourceUnavailabilityProjection(UUID resourceUnavailabilityId, UUID cabinId, UUID tankId,
      Instant disabledAt, Instant restoredAt, long sourceVersion, Instant updatedAt) {
    public ResourceUnavailabilityProjection {
      if (resourceUnavailabilityId == null || disabledAt == null || updatedAt == null
          || (cabinId == null) == (tankId == null) || sourceVersion <= 0) {
        throw new IllegalArgumentException("invalid resource unavailability projection");
      }
    }
  }

  record CleaningPeriodProjection(UUID cleaningPeriodId, UUID washExecutionId,
      UUID resourceAssignmentId, UUID cabinId, String cleaningTrigger, Instant startsAt,
      Instant expectedEndsAt, Instant completedAt, long sourceVersion, Instant updatedAt) {
    public CleaningPeriodProjection {
      if (cleaningPeriodId == null || washExecutionId == null || resourceAssignmentId == null
          || cabinId == null || cleaningTrigger == null || startsAt == null || expectedEndsAt == null
          || !expectedEndsAt.isAfter(startsAt) || sourceVersion <= 0 || updatedAt == null) {
        throw new IllegalArgumentException("invalid cleaning period projection");
      }
    }
  }
}
