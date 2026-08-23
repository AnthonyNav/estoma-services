package com.estoma.washexecution.domain.execution;

import com.estoma.washexecution.domain.shared.DomainRuleViolationException;
import java.time.Instant;
import java.util.UUID;

public final class ResourceAssignment {
  private final UUID resourceAssignmentId;
  private final UUID washExecutionId;
  private final UUID appointmentTimeSlotId;
  private final UUID cabinId;
  private final UUID tankId;
  private final AssignmentType assignmentType;
  private final UUID assignedByAccountId;
  private final Instant assignedAt;
  private UUID releasedByAccountId;
  private Instant releasedAt;
  private ReleaseReason releaseReason;

  public ResourceAssignment(
      UUID resourceAssignmentId,
      UUID washExecutionId,
      UUID appointmentTimeSlotId,
      UUID cabinId,
      UUID tankId,
      AssignmentType assignmentType,
      UUID assignedByAccountId,
      Instant assignedAt) {
    if (resourceAssignmentId == null || washExecutionId == null || appointmentTimeSlotId == null
        || cabinId == null || tankId == null || assignmentType == null || assignedAt == null) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_ASSIGNMENT");
    }
    if (assignmentType == AssignmentType.REASSIGNMENT && assignedByAccountId == null) {
      throw new DomainRuleViolationException("REASSIGNMENT_ACTOR_REQUIRED");
    }
    this.resourceAssignmentId = resourceAssignmentId;
    this.washExecutionId = washExecutionId;
    this.appointmentTimeSlotId = appointmentTimeSlotId;
    this.cabinId = cabinId;
    this.tankId = tankId;
    this.assignmentType = assignmentType;
    this.assignedByAccountId = assignedByAccountId;
    this.assignedAt = assignedAt;
  }

  public static ResourceAssignment reconstitute(
      UUID resourceAssignmentId, UUID washExecutionId, UUID appointmentTimeSlotId, UUID cabinId,
      UUID tankId, AssignmentType assignmentType, UUID assignedByAccountId, Instant assignedAt,
      UUID releasedByAccountId, Instant releasedAt, ReleaseReason releaseReason) {
    ResourceAssignment assignment = new ResourceAssignment(resourceAssignmentId, washExecutionId,
        appointmentTimeSlotId, cabinId, tankId, assignmentType, assignedByAccountId, assignedAt);
    if ((releasedAt == null) != (releaseReason == null)) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_RELEASE_STATE");
    }
    if (releasedAt == null && releasedByAccountId != null) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_RELEASE_STATE");
    }
    if (releasedAt != null && releasedAt.isBefore(assignedAt)) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_RELEASE_TIME");
    }
    assignment.releasedByAccountId = releasedByAccountId;
    assignment.releasedAt = releasedAt;
    assignment.releaseReason = releaseReason;
    return assignment;
  }

  public void release(UUID accountId, Instant at, ReleaseReason reason) {
    if (releasedAt != null) {
      throw new DomainRuleViolationException("RESOURCE_ASSIGNMENT_ALREADY_RELEASED");
    }
    if (at == null || reason == null || at.isBefore(assignedAt)) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_RELEASE");
    }
    releasedByAccountId = accountId;
    releasedAt = at;
    releaseReason = reason;
  }

  public UUID resourceAssignmentId() { return resourceAssignmentId; }
  public UUID washExecutionId() { return washExecutionId; }
  public UUID appointmentTimeSlotId() { return appointmentTimeSlotId; }
  public UUID cabinId() { return cabinId; }
  public UUID tankId() { return tankId; }
  public AssignmentType assignmentType() { return assignmentType; }
  public UUID assignedByAccountId() { return assignedByAccountId; }
  public Instant assignedAt() { return assignedAt; }
  public UUID releasedByAccountId() { return releasedByAccountId; }
  public Instant releasedAt() { return releasedAt; }
  public ReleaseReason releaseReason() { return releaseReason; }
  public boolean active() { return releasedAt == null; }
}
