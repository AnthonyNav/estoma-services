package com.estoma.washexecution.application.command;

import com.estoma.washexecution.application.authorization.AuthorizationEvaluator;
import com.estoma.washexecution.application.authorization.ProjectionReadinessGuard;
import com.estoma.washexecution.application.port.AffectedExecutionRepository;
import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.CommandIdempotencyRepository;
import com.estoma.washexecution.application.port.EntryValidationRepository;
import com.estoma.washexecution.application.port.JsonCodec;
import com.estoma.washexecution.application.port.OutboxRepository;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.ResourceConcurrencyRepository;
import com.estoma.washexecution.application.port.ResourceUnavailabilityRepository;
import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.application.resource.ResourceAllocator;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.AssignmentType;
import com.estoma.washexecution.domain.execution.CleaningPeriod;
import com.estoma.washexecution.domain.execution.CleaningTrigger;
import com.estoma.washexecution.domain.execution.EntryDecision;
import com.estoma.washexecution.domain.execution.EntryValidation;
import com.estoma.washexecution.domain.execution.ExitMaterials;
import com.estoma.washexecution.domain.execution.ReleaseReason;
import com.estoma.washexecution.domain.execution.ResourceAssignment;
import com.estoma.washexecution.domain.execution.ResourceUnavailability;
import com.estoma.washexecution.domain.execution.WashExecution;
import com.estoma.washexecution.domain.execution.WashExecutionStatus;
import com.estoma.washexecution.domain.shared.DomainRuleViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class WashExecutionCommandService {
  private static final String PRODUCER = "wash-execution";

  private final WashExecutionRepository executions;
  private final EntryValidationRepository validations;
  private final ResourceAssignmentRepository assignments;
  private final ResourceUnavailabilityRepository unavailabilities;
  private final AffectedExecutionRepository affectedExecutions;
  private final CleaningPeriodRepository cleanings;
  private final ProjectionRepositories.AppointmentRepository appointments;
  private final ProjectionRepositories.TimeSlotRepository slots;
  private final ProjectionRepositories.ResourceProjectionRepository resources;
  private final ResourceConcurrencyRepository locks;
  private final ResourceAllocator allocator;
  private final AuthorizationEvaluator authorization;
  private final ProjectionReadinessGuard readiness;
  private final OutboxRepository outbox;
  private final CommandIdempotencyRepository idempotency;
  private final JsonCodec json;
  private final Clock clock;
  private final boolean unclassifiedEntryRejectionEnabled;

  public WashExecutionCommandService(
      WashExecutionRepository executions,
      EntryValidationRepository validations,
      ResourceAssignmentRepository assignments,
      ResourceUnavailabilityRepository unavailabilities,
      AffectedExecutionRepository affectedExecutions,
      CleaningPeriodRepository cleanings,
      ProjectionRepositories.AppointmentRepository appointments,
      ProjectionRepositories.TimeSlotRepository slots,
      ProjectionRepositories.ResourceProjectionRepository resources,
      ResourceConcurrencyRepository locks,
      ResourceAllocator allocator,
      AuthorizationEvaluator authorization,
      ProjectionReadinessGuard readiness,
      OutboxRepository outbox,
      CommandIdempotencyRepository idempotency,
      JsonCodec json,
      Clock clock,
      boolean unclassifiedEntryRejectionEnabled) {
    this.executions = executions;
    this.validations = validations;
    this.assignments = assignments;
    this.unavailabilities = unavailabilities;
    this.affectedExecutions = affectedExecutions;
    this.cleanings = cleanings;
    this.appointments = appointments;
    this.slots = slots;
    this.resources = resources;
    this.locks = locks;
    this.allocator = allocator;
    this.authorization = authorization;
    this.readiness = readiness;
    this.outbox = outbox;
    this.idempotency = idempotency;
    this.json = json;
    this.clock = clock;
    this.unclassifiedEntryRejectionEnabled = unclassifiedEntryRejectionEnabled;
  }

  public CommandOutcome dispatch(CommandRequest request) {
    validateEnvelope(request);
    CommandMetadata metadata = request.metadata();
    String operation = request.command().getClass().getSimpleName();
    String key = metadata.idempotencyKey() == null || metadata.idempotencyKey().isBlank()
        ? metadata.messageId().toString()
        : metadata.idempotencyKey();
    String fingerprint = json.fingerprint(Map.of("operation", operation, "command", request.command()));
    Instant now = clock.instant();

    if (!idempotency.insertIfAbsent(metadata.producer(), operation, key, fingerprint, now)) {
      return replayExisting(metadata, operation, key, fingerprint, now);
    }

    try {
      Change change = handle(request.command(), metadata, now);
      Map<String, Object> resultPayload = succeededResult(operation, change.data());
      String resultJson = json.write(resultPayload);
      appendRawResult(metadata, operation + "Succeeded", change.aggregateId(), change.version(), resultJson, now);
      change.events().forEach(event -> appendEvent(metadata, event));
      idempotency.markSucceeded(
          metadata.producer(), operation, key, change.aggregateId(), change.version(), resultJson, now);
      return CommandOutcome.succeeded(change.aggregateId(), change.version(), change.data());
    } catch (CommandRejectedException ex) {
      return reject(metadata, operation, key, aggregateIdOf(request.command()), ex.code(), now);
    } catch (DomainRuleViolationException ex) {
      return reject(metadata, operation, key, aggregateIdOf(request.command()), ex.code(), now);
    }
  }

  private Change handle(WashExecutionCommand command, CommandMetadata metadata, Instant now) {
    if (command instanceof WashExecutionCommand.RegisterWashArrival c) return register(c, metadata);
    if (command instanceof WashExecutionCommand.DecideWashEntry c) return decide(c, metadata);
    if (command instanceof WashExecutionCommand.ResolvePendingReassignment c) return reassign(c, metadata);
    if (command instanceof WashExecutionCommand.DisableOperationalResource c) return disable(c, metadata);
    if (command instanceof WashExecutionCommand.RestoreOperationalResource c) return restore(c, metadata);
    if (command instanceof WashExecutionCommand.SubmitWashExit c) return submitExit(c, metadata);
    if (command instanceof WashExecutionCommand.CompleteWashExecution c) return complete(c, metadata);
    if (command instanceof WashExecutionCommand.CancelWashExecutionByClinic c) return cancel(c, metadata);
    throw new CommandRejectedException("UNSUPPORTED_COMMAND");
  }

  private Change register(WashExecutionCommand.RegisterWashArrival c, CommandMetadata metadata) {
    if (c.washExecutionId() == null || c.appointmentId() == null || c.arrivedAt() == null) {
      throw new CommandRejectedException("INVALID_COMMAND");
    }
    authorization.requireSupervisor(metadata.actor());
    requireAppointmentsReady();
    var appointment = appointments.findById(c.appointmentId())
        .orElseThrow(() -> new CommandRejectedException("APPOINTMENT_NOT_FOUND"));
    var slot = requireSlot(appointment.appointmentTimeSlotId());
    if (!"SCHEDULED".equals(appointment.status())) {
      throw new CommandRejectedException("APPOINTMENT_NOT_SCHEDULED");
    }
    requireActiveSlot(slot);
    if (c.arrivedAt().isBefore(slot.startsAt().minusSeconds(15 * 60L))) {
      throw new CommandRejectedException("ARRIVAL_TOO_EARLY");
    }
    if (c.arrivedAt().isAfter(slot.arrivalToleranceUntil())) {
      throw new CommandRejectedException("ARRIVAL_TOLERANCE_EXCEEDED");
    }
    if (executions.findByAppointmentId(c.appointmentId()).isPresent()) {
      throw new CommandRejectedException("WASH_EXECUTION_ALREADY_EXISTS");
    }

    WashExecution execution = WashExecution.register(
        c.washExecutionId(), c.appointmentId(), appointment.appointmentType(), c.arrivedAt(), clock.instant());
    executions.save(execution, 0);

    Map<String, Object> payload = executionBase(execution);
    payload.put("appointmentType", execution.appointmentType().name());
    payload.put("arrivedAt", execution.arrivedAt());
    return change(execution, data("status", execution.status().name()),
        event("WashArrivalRegistered", execution, c.arrivedAt(), payload));
  }

  private Change decide(WashExecutionCommand.DecideWashEntry c, CommandMetadata metadata) {
    if (c.washExecutionId() == null || c.decision() == null || c.decidedAt() == null
        || c.decidedByAccountId() == null) {
      throw new CommandRejectedException("INVALID_COMMAND");
    }
    UUID supervisorId = authorization.requireSupervisor(metadata.actor());
    requireSameActor(supervisorId, c.decidedByAccountId());
    WashExecution execution = findExecution(c.washExecutionId());
    checkVersion(execution, c.expectedVersion());
    if (execution.status() != WashExecutionStatus.PENDING_ENTRY
        || validations.findByExecutionId(execution.washExecutionId()).isPresent()) {
      throw new CommandRejectedException("INVALID_ENTRY_STATE");
    }
    boolean unclassifiedRejection = c.decision() == EntryDecision.REJECTED
        && c.identityConfirmed() == null
        && c.requirementsSatisfied() == null;
    if (unclassifiedRejection && !unclassifiedEntryRejectionEnabled) {
      throw new CommandRejectedException("UNCLASSIFIED_ENTRY_REJECTION_DISABLED");
    }

    EntryValidation validation = new EntryValidation(
        UUID.randomUUID(), execution.washExecutionId(), c.identityConfirmed(), c.requirementsSatisfied(),
        c.decision(), c.rejectionReason(), supervisorId, c.decidedAt());

    if (c.decision() == EntryDecision.REJECTED) {
      execution.rejectEntry();
      validations.save(validation);
      executions.save(execution, c.expectedVersion());
      Map<String, Object> payload = entryValidationPayload(execution, validation);
      payload.put("rejectionReason", validation.rejectionReason());
      return change(execution, data("status", execution.status().name()),
          event("WashEntryRejected", unclassifiedRejection ? 2 : 1,
              execution, validation.decidedAt(), payload));
    }

    requireAppointmentsReady();
    var appointment = appointments.findById(execution.appointmentId())
        .orElseThrow(() -> new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE"));
    if (!"SCHEDULED".equals(appointment.status())) {
      throw new CommandRejectedException("APPOINTMENT_NOT_SCHEDULED");
    }
    var slot = requireSlot(appointment.appointmentTimeSlotId());
    requireActiveSlot(slot);
    Optional<ProjectionRepositories.ResourceCandidate> candidate = allocator.allocateAutomatic(
        execution.appointmentType(), slot, c.decidedAt());

    if (candidate.isEmpty()) {
      execution.authorizeWithoutResource();
      validations.save(validation);
      executions.save(execution, c.expectedVersion());
      Map<String, Object> authorized = entryValidationPayload(execution, validation);
      Map<String, Object> pending = executionBase(execution);
      pending.put("appointmentType", execution.appointmentType().name());
      pending.put("hadActiveAssignment", false);
      return change(execution, data("status", execution.status().name()),
          event("WashEntryAuthorized", execution, validation.decidedAt(), authorized),
          event("WashExecutionPendingReassignment", execution, validation.decidedAt(), pending));
    }

    ResourceAssignment assignment = new ResourceAssignment(
        UUID.randomUUID(), execution.washExecutionId(), slot.appointmentTimeSlotId(),
        candidate.get().cabinId(), candidate.get().tankId(), AssignmentType.INITIAL, null, c.decidedAt());
    execution.start(c.decidedAt());

    validations.save(validation);
    executions.save(execution, c.expectedVersion());
    assignments.save(assignment);

    Map<String, Object> authorized = entryValidationPayload(execution, validation);
    return change(execution,
        data("status", execution.status().name(), "tankId", assignment.tankId()),
        event("WashEntryAuthorized", execution, validation.decidedAt(), authorized),
        assignmentEvent("WashResourceAssigned", execution, assignment, assignment.assignedAt()),
        startedEvent(execution, assignment));
  }

  private Change reassign(WashExecutionCommand.ResolvePendingReassignment c, CommandMetadata metadata) {
    if (c.washExecutionId() == null || c.cabinId() == null || c.tankId() == null
        || c.resolvedByAccountId() == null || c.resolvedAt() == null) {
      throw new CommandRejectedException("INVALID_COMMAND");
    }
    UUID supervisorId = authorization.requireSupervisor(metadata.actor());
    requireSameActor(supervisorId, c.resolvedByAccountId());
    WashExecution execution = findExecution(c.washExecutionId());
    checkVersion(execution, c.expectedVersion());
    if (execution.status() != WashExecutionStatus.PENDING_REASSIGNMENT) {
      throw new CommandRejectedException("WASH_EXECUTION_NOT_PENDING_REASSIGNMENT");
    }

    requireAppointmentsReady();
    var appointment = appointments.findById(execution.appointmentId())
        .orElseThrow(() -> new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE"));
    if (!"SCHEDULED".equals(appointment.status()) && !"IN_PROGRESS".equals(appointment.status())) {
      throw new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE");
    }
    var slot = requireSlot(appointment.appointmentTimeSlotId());
    ProjectionRepositories.ResourceCandidate selected = allocator.requireSelected(
        execution.appointmentType(), slot, c.cabinId(), c.tankId(), c.resolvedAt());

    ResourceAssignment previous = assignments.findActiveByExecutionId(execution.washExecutionId()).orElse(null);
    boolean wasStarted = execution.startedAt() != null;
    execution.start(c.resolvedAt());
    ResourceAssignment next = new ResourceAssignment(
        UUID.randomUUID(), execution.washExecutionId(), slot.appointmentTimeSlotId(),
        selected.cabinId(), selected.tankId(),
        previous == null ? AssignmentType.INITIAL : AssignmentType.REASSIGNMENT,
        supervisorId,
        c.resolvedAt());

    CleaningPeriod cleaning = null;
    if (previous != null) {
      previous.release(supervisorId, c.resolvedAt(), ReleaseReason.REASSIGNMENT);
      cleaning = prepareCleaningIfNeeded(
          execution, previous, wasStarted, CleaningTrigger.REASSIGNMENT, c.resolvedAt());
    }

    if (previous != null) assignments.release(previous);
    executions.save(execution, c.expectedVersion());
    assignments.save(next);
    if (cleaning != null) cleanings.save(cleaning, 0);

    List<WashExecutionEvents.Event> events = new ArrayList<>();
    if (previous == null) {
      events.add(assignmentEvent("WashResourceAssigned", execution, next, next.assignedAt()));
    } else {
      events.add(reassignmentEvent(execution, previous, next));
    }
    if (!wasStarted) events.add(startedEvent(execution, next));
    if (cleaning != null) events.add(cleaningStartedEvent(execution, cleaning));

    return new Change(
        execution.washExecutionId(), execution.version(),
        data("status", execution.status().name(), "tankId", next.tankId()),
        List.copyOf(events));
  }

  private Change disable(WashExecutionCommand.DisableOperationalResource c, CommandMetadata metadata) {
    if (c.resourceUnavailabilityId() == null || c.causeType() == null || c.reason() == null
        || c.disabledByAccountId() == null || c.disabledAt() == null) {
      throw new CommandRejectedException("INVALID_COMMAND");
    }
    UUID supervisorId = authorization.requireSupervisor(metadata.actor());
    requireSameActor(supervisorId, c.disabledByAccountId());
    readiness.requireReady(
        ProjectionReadinessGuard.WASH_ADMINISTRATION,
        "RESOURCE_CONFIGURATION_UNAVAILABLE");
    if (unavailabilities.findById(c.resourceUnavailabilityId()).isPresent()) {
      throw new CommandRejectedException("RESOURCE_UNAVAILABILITY_ALREADY_EXISTS");
    }
    if (c.detectedDuringWashExecutionId() != null
        && executions.findById(c.detectedDuringWashExecutionId()).isEmpty()) {
      throw new CommandRejectedException("WASH_EXECUTION_NOT_FOUND");
    }

    lockKnownResource(c.cabinId(), c.tankId());
    if (!unavailabilities.findActiveForResource(c.cabinId(), c.tankId()).isEmpty()) {
      throw new CommandRejectedException("RESOURCE_ALREADY_UNAVAILABLE");
    }

    ResourceUnavailability value = new ResourceUnavailability(
        c.resourceUnavailabilityId(), c.detectedDuringWashExecutionId(), c.cabinId(), c.tankId(),
        c.causeType(), c.reason(), supervisorId, c.disabledAt(), 1);
    List<ResourceAssignment> affected = assignments.findActiveByResource(c.cabinId(), c.tankId());
    List<WashExecution> transitioned = new ArrayList<>();
    for (ResourceAssignment assignment : affected) {
      WashExecution execution = executions.findById(assignment.washExecutionId())
          .orElseThrow(() -> new IllegalStateException("WASH_EXECUTION_NOT_FOUND_FOR_ASSIGNMENT"));
      if (execution.status() == WashExecutionStatus.IN_PROGRESS) {
        execution.loseResource();
        transitioned.add(execution);
      }
    }

    unavailabilities.save(value, 0);
    for (ResourceAssignment assignment : affected) {
      affectedExecutions.add(
          value.resourceUnavailabilityId(), assignment.washExecutionId(),
          assignment.resourceAssignmentId(), c.disabledAt());
    }
    for (WashExecution execution : transitioned) {
      executions.save(execution, execution.version() - 1);
    }

    List<WashExecutionEvents.Event> events = new ArrayList<>();
    events.add(resourceDisabledEvent(value));
    for (WashExecution execution : transitioned) {
      Map<String, Object> pending = executionBase(execution);
      pending.put("appointmentType", execution.appointmentType().name());
      pending.put("hadActiveAssignment", true);
      events.add(event(
          "WashExecutionPendingReassignment", execution, c.disabledAt(), pending));
    }
    return new Change(
        value.resourceUnavailabilityId(), value.version(),
        data("resourceUnavailabilityId", value.resourceUnavailabilityId(), "affectedExecutions", affected.size()),
        List.copyOf(events));
  }

  private Change restore(WashExecutionCommand.RestoreOperationalResource c, CommandMetadata metadata) {
    if (c.resourceUnavailabilityId() == null || c.restoredByAccountId() == null || c.restoredAt() == null) {
      throw new CommandRejectedException("INVALID_COMMAND");
    }
    UUID supervisorId = authorization.requireSupervisor(metadata.actor());
    requireSameActor(supervisorId, c.restoredByAccountId());
    ResourceUnavailability value = unavailabilities.findById(c.resourceUnavailabilityId())
        .orElseThrow(() -> new CommandRejectedException("RESOURCE_UNAVAILABILITY_NOT_FOUND"));
    if (value.version() != c.expectedVersion()) {
      throw new CommandRejectedException("VERSION_CONFLICT");
    }
    readiness.requireReady(
        ProjectionReadinessGuard.WASH_ADMINISTRATION,
        "RESOURCE_CONFIGURATION_UNAVAILABLE");
    lockKnownResource(value.cabinId(), value.tankId());
    value.restore(supervisorId, c.restoredAt(), c.resolution());
    unavailabilities.save(value, c.expectedVersion());

    return new Change(
        value.resourceUnavailabilityId(), value.version(), data("status", "RESTORED"),
        List.of(resourceRestoredEvent(value)));
  }

  private Change submitExit(WashExecutionCommand.SubmitWashExit c, CommandMetadata metadata) {
    if (c.washExecutionId() == null || c.submittedByStudentAccountId() == null
        || c.materials() == null || c.submittedAt() == null) {
      throw new CommandRejectedException("INVALID_COMMAND");
    }
    WashExecution execution = findExecution(c.washExecutionId());
    checkVersion(execution, c.expectedVersion());
    requireAppointmentsReady();
    var appointment = appointments.findById(execution.appointmentId())
        .orElseThrow(() -> new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE"));
    UUID studentId = authorization.requireStudentOwner(metadata.actor(), appointment.studentAccountId());
    requireSameActor(studentId, c.submittedByStudentAccountId());
    execution.submitExit(studentId, c.materials(), c.submittedAt());
    executions.save(execution, c.expectedVersion());

    Map<String, Object> payload = executionBase(execution);
    payload.put("submittedByStudentAccountId", studentId);
    addMaterials(payload, c.materials(), "");
    payload.put("submittedAt", c.submittedAt());
    return change(execution, data("status", execution.status().name()),
        event("WashExitSubmitted", execution, c.submittedAt(), payload));
  }

  private Change complete(WashExecutionCommand.CompleteWashExecution c, CommandMetadata metadata) {
    if (c.washExecutionId() == null || c.completedByAccountId() == null
        || c.finalMaterials() == null || c.completedAt() == null) {
      throw new CommandRejectedException("INVALID_COMMAND");
    }
    UUID supervisorId = authorization.requireSupervisor(metadata.actor());
    requireSameActor(supervisorId, c.completedByAccountId());
    WashExecution execution = findExecution(c.washExecutionId());
    checkVersion(execution, c.expectedVersion());
    ResourceAssignment assignment = assignments.findActiveByExecutionId(execution.washExecutionId())
        .orElseThrow(() -> new CommandRejectedException("ACTIVE_RESOURCE_ASSIGNMENT_NOT_FOUND"));

    execution.complete(supervisorId, c.finalMaterials(), c.completedAt());
    assignment.release(supervisorId, c.completedAt(), ReleaseReason.NORMAL_COMPLETION);
    CleaningPeriod cleaning = prepareCleaningIfNeeded(
        execution, assignment, execution.startedAt() != null,
        CleaningTrigger.NORMAL_COMPLETION, c.completedAt());

    assignments.release(assignment);
    executions.save(execution, c.expectedVersion());
    if (cleaning != null) cleanings.save(cleaning, 0);

    List<WashExecutionEvents.Event> events = new ArrayList<>();
    events.add(completedEvent(execution, supervisorId, c.finalMaterials(), c.completedAt()));
    if (cleaning != null) events.add(cleaningStartedEvent(execution, cleaning));
    return new Change(
        execution.washExecutionId(), execution.version(), data("status", execution.status().name()),
        List.copyOf(events));
  }

  private Change cancel(WashExecutionCommand.CancelWashExecutionByClinic c, CommandMetadata metadata) {
    if (c.washExecutionId() == null || c.cancelledByAccountId() == null || c.cancelledAt() == null
        || c.cancellationSubreason() == null) {
      throw new CommandRejectedException("INVALID_COMMAND");
    }
    UUID supervisorId = authorization.requireSupervisor(metadata.actor());
    requireSameActor(supervisorId, c.cancelledByAccountId());
    WashExecution execution = findExecution(c.washExecutionId());
    checkVersion(execution, c.expectedVersion());
    ResourceAssignment assignment = assignments.findActiveByExecutionId(execution.washExecutionId()).orElse(null);
    boolean hadPhysicalUse = execution.startedAt() != null;

    execution.cancelByClinic(
        supervisorId, c.cancellationSubreason(), c.cancellationReason(), c.cancelledAt());
    if ("CAPACITY_LOSS".equals(c.cancellationSubreason())) {
      requireAppointmentsReady();
      var appointment = appointments.findById(execution.appointmentId())
          .orElseThrow(() -> new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE"));
      if (!"SCHEDULED".equals(appointment.status()) && !"IN_PROGRESS".equals(appointment.status())) {
        throw new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE");
      }
      var slot = requireSlot(appointment.appointmentTimeSlotId());
      // The transaction boundary protects these inputs through commit, including new resources.
      // Sample owner time under that gate: delivery/retry may outlive a cleaning deadline.
      // cancelledAt remains the original cancellation/event timestamp, not an availability clock.
      if (!allocator.snapshotCandidates(execution.appointmentType(), slot, clock.instant()).isEmpty()) {
        throw new CommandRejectedException("CAPACITY_ALTERNATIVE_AVAILABLE");
      }
    }
    CleaningPeriod cleaning = null;
    if (assignment != null) {
      assignment.release(supervisorId, c.cancelledAt(), ReleaseReason.CLINIC_CANCELLATION);
      cleaning = prepareCleaningIfNeeded(
          execution, assignment, hadPhysicalUse,
          CleaningTrigger.CLINIC_CANCELLATION, c.cancelledAt());
    }

    if (assignment != null) assignments.release(assignment);
    executions.save(execution, c.expectedVersion());
    if (cleaning != null) cleanings.save(cleaning, 0);

    List<WashExecutionEvents.Event> events = new ArrayList<>();
    events.add(cancelledEvent(execution));
    if (cleaning != null) events.add(cleaningStartedEvent(execution, cleaning));
    return new Change(
        execution.washExecutionId(), execution.version(), data("status", execution.status().name()),
        List.copyOf(events));
  }

  private CleaningPeriod prepareCleaningIfNeeded(
      WashExecution execution,
      ResourceAssignment assignment,
      boolean physicallyUsed,
      CleaningTrigger trigger,
      Instant releasedAt) {
    if (!physicallyUsed || execution.appointmentType() != AppointmentType.IMMUNOCOMPROMISED) {
      return null;
    }
    if (cleanings.findByAssignmentId(assignment.resourceAssignmentId()).isPresent()) {
      return null;
    }
    return new CleaningPeriod(
        UUID.randomUUID(), execution.washExecutionId(), assignment.resourceAssignmentId(),
        assignment.cabinId(), trigger, releasedAt, 1);
  }

  private void lockKnownResource(UUID cabinId, UUID tankId) {
    if ((cabinId == null) == (tankId == null)) {
      throw new CommandRejectedException("INVALID_RESOURCE_COMBINATION");
    }
    if (cabinId != null) {
      resources.findCabin(cabinId)
          .orElseThrow(() -> new CommandRejectedException("RESOURCE_NOT_FOUND"));
      locks.lockCabin(cabinId);
      resources.findCabin(cabinId)
          .orElseThrow(() -> new CommandRejectedException("RESOURCE_NOT_FOUND"));
      return;
    }
    var tank = resources.findTank(tankId)
        .orElseThrow(() -> new CommandRejectedException("RESOURCE_NOT_FOUND"));
    locks.lockCabinAndTank(tank.cabinId(), tankId);
    var lockedTank = resources.findTank(tankId)
        .orElseThrow(() -> new CommandRejectedException("RESOURCE_NOT_FOUND"));
    if (!Objects.equals(tank.cabinId(), lockedTank.cabinId())) {
      throw new CommandRejectedException("INVALID_RESOURCE_COMBINATION");
    }
  }

  private CommandOutcome replayExisting(
      CommandMetadata metadata,
      String operation,
      String key,
      String fingerprint,
      Instant now) {
    var existing = idempotency.find(metadata.producer(), operation, key)
        .orElseThrow(() -> new IllegalStateException("IDEMPOTENCY_RECORD_NOT_FOUND"));
    if (!existing.requestFingerprint().equals(fingerprint)) {
      String code = "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST";
      appendRawResult(
          metadata, operation + "Rejected", aggregateIdOf(null), null,
          json.write(rejectedResult(operation, code)), now);
      return CommandOutcome.rejected(code, null);
    }
    if ("IN_PROGRESS".equals(existing.status())) {
      String code = "COMMAND_IN_PROGRESS";
      appendRawResult(
          metadata, operation + "Rejected", existing.aggregateId(), existing.aggregateVersion(),
          json.write(rejectedResult(operation, code)), now);
      return CommandOutcome.rejected(code, existing.aggregateId());
    }
    String messageType = operation + ("SUCCEEDED".equals(existing.status()) ? "Succeeded" : "Rejected");
    appendRawResult(
        metadata, messageType, existing.aggregateId(), existing.aggregateVersion(), existing.resultJson(), now);
    return CommandOutcome.duplicate(
        existing.errorCode(), existing.aggregateId(), existing.aggregateVersion());
  }

  private CommandOutcome reject(
      CommandMetadata metadata,
      String operation,
      String key,
      UUID aggregateId,
      String code,
      Instant now) {
    Map<String, Object> payload = rejectedResult(operation, code);
    String resultJson = json.write(payload);
    appendRawResult(metadata, operation + "Rejected", aggregateId, null, resultJson, now);
    idempotency.markRejected(
        metadata.producer(), operation, key, aggregateId, code, resultJson, now);
    return CommandOutcome.rejected(code, aggregateId);
  }

  private void appendRawResult(
      CommandMetadata metadata,
      String messageType,
      UUID aggregateId,
      Integer aggregateVersion,
      String payloadJson,
      Instant occurredAt) {
    outbox.append(new OutboxRepository.OutboxMessage(
        UUID.randomUUID(), messageType, 1, "RESULT", PRODUCER, metadata.producer(), occurredAt,
        metadata.operationId(), metadata.idempotencyKey(), aggregateId, aggregateVersion,
        metadata.correlationId(), metadata.messageId(), json.write(metadata.actor()), payloadJson));
  }

  private void appendEvent(CommandMetadata metadata, WashExecutionEvents.Event event) {
    outbox.append(new OutboxRepository.OutboxMessage(
        UUID.randomUUID(), event.type(), event.messageVersion(), "EVENT", PRODUCER, event.type(), event.occurredAt(),
        metadata.operationId(), metadata.idempotencyKey(), event.aggregateId(), event.aggregateVersion(),
        metadata.correlationId(), metadata.messageId(), json.write(metadata.actor()), json.write(event.payload())));
  }

  private static Map<String, Object> succeededResult(String operation, Map<String, Object> data) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "SUCCEEDED");
    payload.put("operation", operation);
    payload.put("data", data);
    return payload;
  }

  private static Map<String, Object> rejectedResult(String operation, String code) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "REJECTED");
    payload.put("operation", operation);
    payload.put("error", error);
    return payload;
  }

  private static Map<String, Object> executionBase(WashExecution execution) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("washExecutionId", execution.washExecutionId());
    payload.put("appointmentId", execution.appointmentId());
    payload.put("executionStatus", execution.status().name());
    payload.put("executionVersion", execution.version());
    return payload;
  }

  private static Map<String, Object> entryValidationPayload(
      WashExecution execution, EntryValidation validation) {
    Map<String, Object> payload = executionBase(execution);
    payload.put("entryValidationId", validation.entryValidationId());
    if (validation.identityConfirmed() != null) {
      payload.put("identityConfirmed", validation.identityConfirmed());
      payload.put("requirementsSatisfied", validation.requirementsSatisfied());
    }
    payload.put("decidedByAccountId", validation.decidedByAccountId());
    payload.put("decidedAt", validation.decidedAt());
    return payload;
  }

  private WashExecutionEvents.Event assignmentEvent(
      String eventType,
      WashExecution execution,
      ResourceAssignment assignment,
      Instant occurredAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("resourceAssignmentId", assignment.resourceAssignmentId());
    payload.put("washExecutionId", execution.washExecutionId());
    payload.put("appointmentId", execution.appointmentId());
    payload.put("appointmentTimeSlotId", assignment.appointmentTimeSlotId());
    payload.put("cabinId", assignment.cabinId());
    payload.put("tankId", assignment.tankId());
    payload.put("assignmentType", assignment.assignmentType().name());
    payload.put("assignedByAccountId", assignment.assignedByAccountId());
    payload.put("assignedAt", assignment.assignedAt());
    return new WashExecutionEvents.Event(
        eventType, execution.washExecutionId(), execution.version(), occurredAt, payload);
  }

  private WashExecutionEvents.Event reassignmentEvent(
      WashExecution execution,
      ResourceAssignment previous,
      ResourceAssignment next) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("washExecutionId", execution.washExecutionId());
    payload.put("appointmentId", execution.appointmentId());
    payload.put("appointmentTimeSlotId", next.appointmentTimeSlotId());
    payload.put("previousResourceAssignmentId", previous.resourceAssignmentId());
    payload.put("previousCabinId", previous.cabinId());
    payload.put("previousTankId", previous.tankId());
    payload.put("releasedAt", previous.releasedAt());
    payload.put("releaseReason", previous.releaseReason().name());
    payload.put("newResourceAssignmentId", next.resourceAssignmentId());
    payload.put("newCabinId", next.cabinId());
    payload.put("newTankId", next.tankId());
    payload.put("assignmentType", next.assignmentType().name());
    payload.put("assignedByAccountId", next.assignedByAccountId());
    payload.put("assignedAt", next.assignedAt());
    return new WashExecutionEvents.Event(
        "WashResourceReassigned", execution.washExecutionId(), execution.version(), next.assignedAt(), payload);
  }

  private WashExecutionEvents.Event startedEvent(
      WashExecution execution, ResourceAssignment assignment) {
    Map<String, Object> payload = executionBase(execution);
    payload.put("appointmentType", execution.appointmentType().name());
    payload.put("appointmentTimeSlotId", assignment.appointmentTimeSlotId());
    payload.put("resourceAssignmentId", assignment.resourceAssignmentId());
    payload.put("cabinId", assignment.cabinId());
    payload.put("tankId", assignment.tankId());
    payload.put("startedAt", execution.startedAt());
    return event("WashExecutionStarted", execution, execution.startedAt(), payload);
  }

  private WashExecutionEvents.Event resourceDisabledEvent(ResourceUnavailability value) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("resourceUnavailabilityId", value.resourceUnavailabilityId());
    payload.put("cabinId", value.cabinId());
    payload.put("tankId", value.tankId());
    payload.put("detectedDuringWashExecutionId", value.detectedDuringWashExecutionId());
    payload.put("causeType", value.causeType().name());
    payload.put("reason", value.reason());
    payload.put("disabledByAccountId", value.disabledByAccountId());
    payload.put("disabledAt", value.disabledAt());
    payload.put("resourceUnavailabilityVersion", value.version());
    return new WashExecutionEvents.Event(
        "OperationalResourceDisabled", value.resourceUnavailabilityId(), value.version(), value.disabledAt(), payload);
  }

  private WashExecutionEvents.Event resourceRestoredEvent(ResourceUnavailability value) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("resourceUnavailabilityId", value.resourceUnavailabilityId());
    payload.put("cabinId", value.cabinId());
    payload.put("tankId", value.tankId());
    payload.put("restoredByAccountId", value.restoredByAccountId());
    payload.put("restoredAt", value.restoredAt());
    payload.put("resolution", value.resolution());
    payload.put("resourceUnavailabilityVersion", value.version());
    return new WashExecutionEvents.Event(
        "OperationalResourceRestored", value.resourceUnavailabilityId(), value.version(), value.restoredAt(), payload);
  }

  private WashExecutionEvents.Event completedEvent(
      WashExecution execution, UUID supervisorId, ExitMaterials materials, Instant completedAt) {
    Map<String, Object> payload = executionBase(execution);
    payload.put("appointmentType", execution.appointmentType().name());
    payload.put("completedByAccountId", supervisorId);
    payload.put("completedAt", completedAt);
    addMaterials(payload, materials, "final");
    return event("WashExecutionCompleted", execution, completedAt, payload);
  }

  private WashExecutionEvents.Event cancelledEvent(WashExecution execution) {
    Map<String, Object> payload = executionBase(execution);
    payload.put("appointmentType", execution.appointmentType().name());
    payload.put("cancellationType", execution.cancellationType().name());
    payload.put("cancellationSubreason", execution.cancellationSubreason());
    payload.put("cancellationReason", execution.cancellationReason());
    payload.put("cancelledByAccountId", execution.cancelledByAccountId());
    payload.put("cancelledAt", execution.cancelledAt());
    payload.put("startedAt", execution.startedAt());
    return event("WashExecutionCancelled", execution, execution.cancelledAt(), payload);
  }

  private WashExecutionEvents.Event cleaningStartedEvent(
      WashExecution execution, CleaningPeriod cleaning) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cleaningPeriodId", cleaning.cleaningPeriodId());
    payload.put("washExecutionId", cleaning.washExecutionId());
    payload.put("appointmentId", execution.appointmentId());
    payload.put("resourceAssignmentId", cleaning.resourceAssignmentId());
    payload.put("cabinId", cleaning.cabinId());
    payload.put("cleaningTrigger", cleaning.cleaningTrigger().name());
    payload.put("startsAt", cleaning.startsAt());
    payload.put("expectedEndsAt", cleaning.expectedEndsAt());
    payload.put("cleaningPeriodVersion", cleaning.version());
    return new WashExecutionEvents.Event(
        "CleaningPeriodStarted", cleaning.cleaningPeriodId(), cleaning.version(), cleaning.startsAt(), payload);
  }

  private static WashExecutionEvents.Event event(
      String type, WashExecution execution, Instant occurredAt, Map<String, Object> payload) {
    return new WashExecutionEvents.Event(
        type, execution.washExecutionId(), execution.version(), occurredAt, payload);
  }

  private static WashExecutionEvents.Event event(
      String type, int messageVersion, WashExecution execution, Instant occurredAt,
      Map<String, Object> payload) {
    return new WashExecutionEvents.Event(
        type, messageVersion, execution.washExecutionId(), execution.version(), occurredAt, payload);
  }

  private static void addMaterials(
      Map<String, Object> payload, ExitMaterials materials, String prefix) {
    String p = prefix == null ? "" : prefix;
    payload.put(field(p, "packageCount"), materials.packageCount());
    payload.put(field(p, "greenPaperCassette8Count"), materials.greenPaperCassette8Count());
    payload.put(field(p, "greenPaperCassette10Count"), materials.greenPaperCassette10Count());
    payload.put(field(p, "witnessTapePortionCount"), materials.witnessTapePortionCount());
  }

  private static String field(String prefix, String base) {
    if (prefix.isEmpty()) return base;
    return prefix + Character.toUpperCase(base.charAt(0)) + base.substring(1);
  }

  private void requireAppointmentsReady() {
    readiness.requireReady(
        ProjectionReadinessGuard.APPOINTMENTS,
        "APPOINTMENT_CONTEXT_UNAVAILABLE");
  }

  private ProjectionRepositories.TimeSlotContext requireSlot(UUID slotId) {
    return slots.findById(slotId)
        .orElseThrow(() -> new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE"));
  }

  private static void requireActiveSlot(ProjectionRepositories.TimeSlotContext slot) {
    if (!"ACTIVE".equals(slot.status())) {
      throw new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE");
    }
  }

  private WashExecution findExecution(UUID executionId) {
    return executions.findById(executionId)
        .orElseThrow(() -> new CommandRejectedException("WASH_EXECUTION_NOT_FOUND"));
  }

  private static void checkVersion(WashExecution execution, int expectedVersion) {
    if (execution.version() != expectedVersion) {
      throw new CommandRejectedException("VERSION_CONFLICT");
    }
  }

  private static void requireSameActor(UUID actorAccountId, UUID functionalAccountId) {
    if (!Objects.equals(actorAccountId, functionalAccountId)) {
      throw new CommandRejectedException("ACTOR_NOT_AUTHORIZED");
    }
  }

  private static void validateEnvelope(CommandRequest request) {
    if (request == null || request.metadata() == null || request.command() == null) {
      throw new IllegalArgumentException("INVALID_COMMAND_REQUEST");
    }
    CommandMetadata metadata = request.metadata();
    if (metadata.messageId() == null || metadata.correlationId() == null
        || metadata.producer() == null || metadata.producer().isBlank() || metadata.actor() == null) {
      throw new IllegalArgumentException("INVALID_COMMAND_METADATA");
    }
  }

  private static UUID aggregateIdOf(WashExecutionCommand command) {
    if (command instanceof WashExecutionCommand.RegisterWashArrival c) return c.washExecutionId();
    if (command instanceof WashExecutionCommand.DecideWashEntry c) return c.washExecutionId();
    if (command instanceof WashExecutionCommand.ResolvePendingReassignment c) return c.washExecutionId();
    if (command instanceof WashExecutionCommand.DisableOperationalResource c) return c.resourceUnavailabilityId();
    if (command instanceof WashExecutionCommand.RestoreOperationalResource c) return c.resourceUnavailabilityId();
    if (command instanceof WashExecutionCommand.SubmitWashExit c) return c.washExecutionId();
    if (command instanceof WashExecutionCommand.CompleteWashExecution c) return c.washExecutionId();
    if (command instanceof WashExecutionCommand.CancelWashExecutionByClinic c) return c.washExecutionId();
    return null;
  }

  private static Change change(
      WashExecution execution,
      Map<String, Object> data,
      WashExecutionEvents.Event... events) {
    return new Change(
        execution.washExecutionId(), execution.version(), data, List.of(events));
  }

  private static Map<String, Object> data(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      result.put((String) values[i], values[i + 1]);
    }
    return result;
  }

  private record Change(
      UUID aggregateId,
      int version,
      Map<String, Object> data,
      List<WashExecutionEvents.Event> events) {}
}
