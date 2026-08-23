package com.estoma.washexecution.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WashExecutionIntegrationQueryRepository {
  EntityContext get(UUID appointmentId);
  Page list(LocalDate serviceDate, int pageSize, String pageToken);

  record ExecutionSnapshot(UUID washExecutionId,UUID appointmentId,String appointmentType,String status,
      Instant arrivedAt,Instant startedAt,Instant entryAuthorizedAt,Instant completedAt,Instant cancelledAt,
      String cancellationType,int version) {}
  record UnavailabilitySnapshot(UUID resourceUnavailabilityId,UUID cabinId,UUID tankId,Instant disabledAt,Instant restoredAt,int version) {}
  record CleaningSnapshot(UUID cleaningPeriodId,UUID washExecutionId,UUID resourceAssignmentId,UUID cabinId,
      String cleaningTrigger,Instant startsAt,Instant expectedEndsAt,Instant completedAt,int version) {}
  record EntityContext(UUID appointmentId,ExecutionSnapshot washExecution,List<UnavailabilitySnapshot> resourceUnavailabilities,List<CleaningSnapshot> cleaningPeriods) {}
  record Page(LocalDate serviceDate,List<ExecutionSnapshot> executions,List<UnavailabilitySnapshot> resourceUnavailabilities,
      List<CleaningSnapshot> cleaningPeriods,String nextPageToken,Instant snapshotGeneratedAt) {}
}
