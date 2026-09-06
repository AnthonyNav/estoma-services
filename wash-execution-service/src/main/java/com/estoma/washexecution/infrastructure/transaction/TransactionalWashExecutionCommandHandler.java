package com.estoma.washexecution.infrastructure.transaction;

import com.estoma.washexecution.application.command.CommandOutcome;
import com.estoma.washexecution.application.command.CommandRequest;
import com.estoma.washexecution.application.command.WashExecutionCommand;
import com.estoma.washexecution.application.command.WashExecutionCommandService;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.ResourceConcurrencyRepository;
import com.estoma.washexecution.application.port.ResourceConcurrencyRepository.ResourceLock;
import com.estoma.washexecution.application.port.WashExecutionCommandUseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class TransactionalWashExecutionCommandHandler implements WashExecutionCommandUseCase {
  private final WashExecutionCommandService service;
  private final ResourceAssignmentRepository assignments;
  private final ProjectionRepositories.ResourceProjectionRepository resources;
  private final ResourceConcurrencyRepository resourceLocks;
  private final TransactionTemplate transactions;

  public TransactionalWashExecutionCommandHandler(
      WashExecutionCommandService service,
      ResourceAssignmentRepository assignments,
      ProjectionRepositories.ResourceProjectionRepository resources,
      ResourceConcurrencyRepository resourceLocks,
      PlatformTransactionManager transactionManager) {
    this.service = service;
    this.assignments = assignments;
    this.resources = resources;
    this.resourceLocks = resourceLocks;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public CommandOutcome dispatch(CommandRequest request) {
    return Objects.requireNonNull(
        transactions.execute(status -> {
          if (request != null
              && request.command() instanceof WashExecutionCommand.CancelWashExecutionByClinic cancel
              && "CAPACITY_LOSS".equals(cancel.cancellationSubreason())) {
            resourceLocks.lockCapacityDecision();
          }
          preLockReleaseResources(request);
          return service.dispatch(request);
        }),
        "transaction returned no command outcome");
  }

  private void preLockReleaseResources(CommandRequest request) {
    if (request == null || request.command() == null) return;

    if (request.command() instanceof WashExecutionCommand.ResolvePendingReassignment command) {
      List<ResourceLock> locks = new ArrayList<>();
      addRequestedIfKnown(locks, command.cabinId(), command.tankId());
      addCurrentAssignmentIfKnown(locks, command.washExecutionId());
      if (!locks.isEmpty()) resourceLocks.lockResourceSet(locks);
      return;
    }

    if (request.command() instanceof WashExecutionCommand.CompleteWashExecution command) {
      lockCurrentAssignmentIfKnown(command.washExecutionId());
      return;
    }

    if (request.command() instanceof WashExecutionCommand.CancelWashExecutionByClinic command) {
      lockCurrentAssignmentIfKnown(command.washExecutionId());
    }
  }

  private void lockCurrentAssignmentIfKnown(UUID executionId) {
    if (executionId == null) return;
    assignments.findActiveByExecutionId(executionId)
        .flatMap(assignment -> knownLock(assignment.cabinId(), assignment.tankId()))
        .ifPresent(lock -> resourceLocks.lockResourceSet(List.of(lock)));
  }

  private void addCurrentAssignmentIfKnown(List<ResourceLock> locks, UUID executionId) {
    if (executionId == null) return;
    assignments.findActiveByExecutionId(executionId)
        .flatMap(assignment -> knownLock(assignment.cabinId(), assignment.tankId()))
        .ifPresent(locks::add);
  }

  private void addRequestedIfKnown(List<ResourceLock> locks, UUID cabinId, UUID tankId) {
    knownLock(cabinId, tankId).ifPresent(locks::add);
  }

  private java.util.Optional<ResourceLock> knownLock(UUID cabinId, UUID tankId) {
    if (cabinId == null || tankId == null || resources.findCabin(cabinId).isEmpty()) {
      return java.util.Optional.empty();
    }
    return resources.findTank(tankId)
        .filter(tank -> Objects.equals(tank.cabinId(), cabinId))
        .map(tank -> new ResourceLock(cabinId, tankId));
  }
}
