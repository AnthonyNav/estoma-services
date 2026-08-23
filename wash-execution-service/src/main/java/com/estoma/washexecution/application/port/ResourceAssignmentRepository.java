package com.estoma.washexecution.application.port;

import com.estoma.washexecution.domain.execution.ResourceAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourceAssignmentRepository {
  Optional<ResourceAssignment> findActiveByExecutionId(UUID executionId);
  List<ResourceAssignment> findByExecutionId(UUID executionId);
  List<ResourceAssignment> findActiveByResource(UUID cabinId, UUID tankId);
  int countActiveByTankAndSlot(UUID tankId, UUID appointmentTimeSlotId);
  void save(ResourceAssignment assignment);
  void release(ResourceAssignment assignment);
}
