package com.estoma.washexecution.application.port;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

public interface ResourceConcurrencyRepository {
  void lockCabin(UUID cabinId);

  void lockTank(UUID cabinId, UUID tankId);

  default void lockCabinAndTank(UUID cabinId, UUID tankId) {
    lockCabin(cabinId);
    lockTank(cabinId, tankId);
  }

  default void lockResourceSet(Collection<ResourceLock> resources) {
    var distinct = resources.stream()
        .filter(Objects::nonNull)
        .distinct()
        .sorted(Comparator.comparing(ResourceLock::cabinId).thenComparing(ResourceLock::tankId))
        .toList();

    distinct.stream()
        .map(ResourceLock::cabinId)
        .distinct()
        .sorted()
        .forEach(this::lockCabin);

    distinct.forEach(resource -> lockTank(resource.cabinId(), resource.tankId()));
  }

  record ResourceLock(UUID cabinId, UUID tankId) {
    public ResourceLock {
      Objects.requireNonNull(cabinId, "cabinId");
      Objects.requireNonNull(tankId, "tankId");
    }
  }
}
