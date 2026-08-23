package com.estoma.washexecution.application.command;

import com.estoma.washexecution.domain.execution.EntryDecision;
import com.estoma.washexecution.domain.execution.ExitMaterials;
import com.estoma.washexecution.domain.execution.UnavailabilityCauseType;
import java.time.Instant;
import java.util.UUID;

public sealed interface WashExecutionCommand permits
    WashExecutionCommand.RegisterWashArrival,
    WashExecutionCommand.DecideWashEntry,
    WashExecutionCommand.ResolvePendingReassignment,
    WashExecutionCommand.DisableOperationalResource,
    WashExecutionCommand.RestoreOperationalResource,
    WashExecutionCommand.SubmitWashExit,
    WashExecutionCommand.CompleteWashExecution,
    WashExecutionCommand.CancelWashExecutionByClinic {

  record RegisterWashArrival(UUID washExecutionId, UUID appointmentId, Instant arrivedAt)
      implements WashExecutionCommand {}

  record DecideWashEntry(
      UUID washExecutionId,
      boolean identityConfirmed,
      boolean requirementsSatisfied,
      EntryDecision decision,
      String rejectionReason,
      UUID decidedByAccountId,
      Instant decidedAt,
      int expectedVersion) implements WashExecutionCommand {}

  record ResolvePendingReassignment(
      UUID washExecutionId,
      UUID cabinId,
      UUID tankId,
      UUID resolvedByAccountId,
      Instant resolvedAt,
      int expectedVersion) implements WashExecutionCommand {}

  record DisableOperationalResource(
      UUID resourceUnavailabilityId,
      UUID cabinId,
      UUID tankId,
      UnavailabilityCauseType causeType,
      String reason,
      UUID disabledByAccountId,
      UUID detectedDuringWashExecutionId,
      Instant disabledAt) implements WashExecutionCommand {}

  record RestoreOperationalResource(
      UUID resourceUnavailabilityId,
      String resolution,
      UUID restoredByAccountId,
      Instant restoredAt,
      int expectedVersion) implements WashExecutionCommand {}

  record SubmitWashExit(
      UUID washExecutionId,
      UUID submittedByStudentAccountId,
      ExitMaterials materials,
      Instant submittedAt,
      int expectedVersion) implements WashExecutionCommand {}

  record CompleteWashExecution(
      UUID washExecutionId,
      UUID completedByAccountId,
      ExitMaterials finalMaterials,
      Instant completedAt,
      int expectedVersion) implements WashExecutionCommand {}

  record CancelWashExecutionByClinic(
      UUID washExecutionId,
      String cancellationSubreason,
      String cancellationReason,
      UUID cancelledByAccountId,
      Instant cancelledAt,
      int expectedVersion) implements WashExecutionCommand {}
}
