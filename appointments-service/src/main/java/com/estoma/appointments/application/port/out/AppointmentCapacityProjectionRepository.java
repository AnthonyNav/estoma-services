package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentCapacityProjectionRepository {
  CapacityProjection save(CapacityProjection projection);
  Optional<CapacityProjection> findBySlotId(UUID slotId);
  Optional<CapacityProjection> findBySlotIdForUpdate(UUID slotId);
  boolean updateIfVersion(CapacityProjection projection, long expectedVersion);

  record CapacityProjection(UUID slotId, int normalCapacity, int journeyCapacity,
      int immunocompromisedCapacity, long version, Instant calculatedAt) {
    public CapacityProjection {
      if (slotId == null || normalCapacity < 0 || journeyCapacity < 0
          || immunocompromisedCapacity < 0 || version <= 0 || calculatedAt == null) {
        throw new IllegalArgumentException("invalid capacity projection");
      }
    }
  }
}
