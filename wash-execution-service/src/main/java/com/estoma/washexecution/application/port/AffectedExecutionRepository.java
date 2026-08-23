package com.estoma.washexecution.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AffectedExecutionRepository {
  void add(UUID resourceUnavailabilityId, UUID washExecutionId, UUID resourceAssignmentId, Instant affectedAt);
  List<AffectedExecution> findByUnavailabilityId(UUID resourceUnavailabilityId);

  record AffectedExecution(
      UUID resourceUnavailabilityId,
      UUID washExecutionId,
      UUID resourceAssignmentId,
      Instant affectedAt) {}
}
