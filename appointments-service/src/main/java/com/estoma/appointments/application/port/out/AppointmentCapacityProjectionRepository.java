package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.estoma.appointments.domain.model.AppointmentType;

public interface AppointmentCapacityProjectionRepository {
  CapacityProjection save(CapacityProjection projection);
  Optional<CapacityProjection> findBySlotId(UUID slotId);
  Optional<CapacityProjection> findBySlotIdForUpdate(UUID slotId);
  boolean updateIfVersion(CapacityProjection projection, long expectedVersion);
  /** Atomic low-level reservation primitive used by application transactions. */
  boolean tryReserve(UUID slotId, AppointmentType appointmentType);

  record CapacityProjection(UUID slotId, int normalCapacity, int journeyCapacity,
      int immunocompromisedCapacity, int normalDemand, int journeyDemand,
      int immunocompromisedDemand, long version, Instant calculatedAt) {
    public CapacityProjection {
      if (slotId == null || normalCapacity < 0 || journeyCapacity < 0 || immunocompromisedCapacity < 0
          || normalDemand < 0 || journeyDemand < 0 || immunocompromisedDemand < 0 || version <= 0
          || calculatedAt == null) throw new IllegalArgumentException("invalid capacity projection");
    }
  }
}
