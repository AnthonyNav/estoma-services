package com.estoma.washexecution.application.port;

import com.estoma.washexecution.domain.execution.ResourceUnavailability;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourceUnavailabilityRepository {
  Optional<ResourceUnavailability> findById(UUID id);
  List<ResourceUnavailability> findActiveForResource(UUID cabinId, UUID tankId);
  void save(ResourceUnavailability unavailability, int expectedVersion);
}
