package com.estoma.washexecution.application.port;

import java.util.UUID;

public interface ResourceConcurrencyRepository {
  void lockCabin(UUID cabinId);

  void lockTank(UUID cabinId, UUID tankId);

  default void lockCabinAndTank(UUID cabinId, UUID tankId) {
    lockCabin(cabinId);
    lockTank(cabinId, tankId);
  }
}
