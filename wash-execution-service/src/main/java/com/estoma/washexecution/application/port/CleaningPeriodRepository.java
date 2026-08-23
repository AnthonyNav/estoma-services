package com.estoma.washexecution.application.port;

import com.estoma.washexecution.domain.execution.CleaningPeriod;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CleaningPeriodRepository {
  Optional<CleaningPeriod> findByAssignmentId(UUID assignmentId);
  List<CleaningPeriod> findExpiredForUpdate(Instant now);
  List<CleaningPeriod> findOverlapping(UUID cabinId, Instant startsAt, Instant endsAt);
  void save(CleaningPeriod period, int expectedVersion);
}
