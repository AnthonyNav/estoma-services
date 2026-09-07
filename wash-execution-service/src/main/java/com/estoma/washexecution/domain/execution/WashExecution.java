package com.estoma.washexecution.domain.execution;

import com.estoma.washexecution.domain.shared.DomainRuleViolationException;
import java.time.Instant;
import java.util.UUID;

public final class WashExecution {
  private final UUID washExecutionId;
  private final UUID appointmentId;
  private final AppointmentType appointmentType;
  private final Instant arrivedAt;
  private Instant startedAt;
  private UUID exitSubmittedByAccountId;
  private Instant exitSubmittedAt;
  private ExitMaterials exitMaterials;
  private UUID completedByAccountId;
  private Instant completedAt;
  private CancellationType cancellationType;
  private String cancellationSubreason;
  private String cancellationReason;
  private UUID cancelledByAccountId;
  private Instant cancelledAt;
  private WashExecutionStatus status;
  private int version;

  private WashExecution(UUID id, UUID appointmentId, AppointmentType type, Instant arrivedAt) {
    if (id == null || appointmentId == null || type == null || arrivedAt == null) {
      throw new DomainRuleViolationException("INVALID_WASH_EXECUTION");
    }
    this.washExecutionId = id;
    this.appointmentId = appointmentId;
    this.appointmentType = type;
    this.arrivedAt = arrivedAt;
    this.status = WashExecutionStatus.PENDING_ENTRY;
    this.version = 1;
  }

  public static WashExecution register(
      UUID id, UUID appointmentId, AppointmentType type, Instant arrivedAt, Instant now) {
    if (now == null || arrivedAt == null || arrivedAt.isAfter(now)) {
      throw new DomainRuleViolationException("INVALID_ARRIVAL_TIME");
    }
    return new WashExecution(id, appointmentId, type, arrivedAt);
  }

  public static WashExecution reconstitute(
      UUID id, UUID appointmentId, AppointmentType type, Instant arrivedAt, Instant startedAt,
      UUID exitSubmittedBy, Instant exitSubmittedAt, ExitMaterials exitMaterials,
      UUID completedBy, Instant completedAt, CancellationType cancellationType,
      String cancellationSubreason, String cancellationReason, UUID cancelledBy, Instant cancelledAt,
      WashExecutionStatus status, int version) {
    if (status == null || version < 1) throw new DomainRuleViolationException("INVALID_WASH_EXECUTION");
    WashExecution execution = new WashExecution(id, appointmentId, type, arrivedAt);
    execution.startedAt = startedAt;
    execution.exitSubmittedByAccountId = exitSubmittedBy;
    execution.exitSubmittedAt = exitSubmittedAt;
    execution.exitMaterials = exitMaterials;
    execution.completedByAccountId = completedBy;
    execution.completedAt = completedAt;
    execution.cancellationType = cancellationType;
    execution.cancellationSubreason = cancellationSubreason;
    execution.cancellationReason = cancellationReason;
    execution.cancelledByAccountId = cancelledBy;
    execution.cancelledAt = cancelledAt;
    execution.status = status;
    execution.version = version;
    return execution;
  }

  public void rejectEntry() {
    require(WashExecutionStatus.PENDING_ENTRY);
    status = WashExecutionStatus.ENTRY_REJECTED;
    version++;
  }

  public void authorizeWithoutResource() {
    require(WashExecutionStatus.PENDING_ENTRY);
    status = WashExecutionStatus.PENDING_REASSIGNMENT;
    version++;
  }

  public void start(Instant now) {
    if (status != WashExecutionStatus.PENDING_ENTRY && status != WashExecutionStatus.PENDING_REASSIGNMENT) {
      throw new DomainRuleViolationException("INVALID_WASH_EXECUTION_STATUS");
    }
    if (now == null || now.isBefore(arrivedAt)) {
      throw new DomainRuleViolationException("INVALID_START_TIME");
    }
    if (startedAt == null) startedAt = now;
    status = WashExecutionStatus.IN_PROGRESS;
    version++;
  }

  public void loseResource() {
    require(WashExecutionStatus.IN_PROGRESS);
    status = WashExecutionStatus.PENDING_REASSIGNMENT;
    version++;
  }

  public void submitExit(UUID accountId, ExitMaterials materials, Instant at) {
    require(WashExecutionStatus.IN_PROGRESS);
    if (accountId == null || at == null || materials == null || startedAt == null || at.isBefore(startedAt)) {
      throw new DomainRuleViolationException("INVALID_EXIT");
    }
    exitSubmittedByAccountId = accountId;
    exitSubmittedAt = at;
    exitMaterials = materials;
    status = WashExecutionStatus.EXIT_SUBMITTED;
    version++;
  }

  public void complete(UUID accountId, ExitMaterials finalMaterials, Instant at) {
    require(WashExecutionStatus.EXIT_SUBMITTED);
    if (accountId == null || at == null || finalMaterials == null || exitSubmittedAt == null
        || at.isBefore(exitSubmittedAt)) {
      throw new DomainRuleViolationException("INVALID_COMPLETION");
    }
    finish(accountId, finalMaterials, at);
  }

  public void completeDirectly(UUID accountId, ExitMaterials finalMaterials, Instant at) {
    require(WashExecutionStatus.IN_PROGRESS);
    if (accountId == null || at == null || finalMaterials == null || startedAt == null
        || at.isBefore(startedAt)) {
      throw new DomainRuleViolationException("INVALID_COMPLETION");
    }
    finish(accountId, finalMaterials, at);
  }

  private void finish(UUID accountId, ExitMaterials finalMaterials, Instant at) {
    completedByAccountId = accountId;
    completedAt = at;
    exitMaterials = finalMaterials;
    status = WashExecutionStatus.COMPLETED;
    version++;
  }

  public void cancelByClinic(UUID accountId, String subreason, String reason, Instant at) {
    require(WashExecutionStatus.PENDING_REASSIGNMENT);
    if (accountId == null || subreason == null || subreason.isBlank() || at == null || at.isBefore(arrivedAt)) {
      throw new DomainRuleViolationException("INVALID_CLINIC_CANCELLATION");
    }
    cancellationType = CancellationType.CLINIC;
    cancellationSubreason = subreason;
    cancellationReason = reason == null || reason.isBlank() ? null : reason;
    cancelledByAccountId = accountId;
    cancelledAt = at;
    status = WashExecutionStatus.CANCELLED;
    version++;
  }

  private void require(WashExecutionStatus expected) {
    if (status != expected) throw new DomainRuleViolationException("INVALID_WASH_EXECUTION_STATUS");
  }

  public UUID washExecutionId() { return washExecutionId; }
  public UUID appointmentId() { return appointmentId; }
  public AppointmentType appointmentType() { return appointmentType; }
  public Instant arrivedAt() { return arrivedAt; }
  public Instant startedAt() { return startedAt; }
  public UUID exitSubmittedByAccountId() { return exitSubmittedByAccountId; }
  public Instant exitSubmittedAt() { return exitSubmittedAt; }
  public ExitMaterials exitMaterials() { return exitMaterials; }
  public UUID completedByAccountId() { return completedByAccountId; }
  public Instant completedAt() { return completedAt; }
  public CancellationType cancellationType() { return cancellationType; }
  public String cancellationSubreason() { return cancellationSubreason; }
  public String cancellationReason() { return cancellationReason; }
  public UUID cancelledByAccountId() { return cancelledByAccountId; }
  public Instant cancelledAt() { return cancelledAt; }
  public WashExecutionStatus status() { return status; }
  public int version() { return version; }
}
