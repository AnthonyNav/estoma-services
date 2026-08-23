package com.estoma.washexecution.domain.execution;

import com.estoma.washexecution.domain.shared.DomainRuleViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class CleaningPeriod {
  private final UUID cleaningPeriodId;
  private final UUID washExecutionId;
  private final UUID resourceAssignmentId;
  private final UUID cabinId;
  private final CleaningTrigger cleaningTrigger;
  private final Instant startsAt;
  private final Instant expectedEndsAt;
  private Instant completedAt;
  private int version;

  public CleaningPeriod(UUID id, UUID executionId, UUID assignmentId, UUID cabinId,
      CleaningTrigger trigger, Instant startsAt, int version) {
    if (id == null || executionId == null || assignmentId == null || cabinId == null
        || trigger == null || startsAt == null || version < 1) {
      throw new DomainRuleViolationException("INVALID_CLEANING_PERIOD");
    }
    this.cleaningPeriodId = id;
    this.washExecutionId = executionId;
    this.resourceAssignmentId = assignmentId;
    this.cabinId = cabinId;
    this.cleaningTrigger = trigger;
    this.startsAt = startsAt;
    this.expectedEndsAt = startsAt.plus(Duration.ofHours(3));
    this.version = version;
  }

  public static CleaningPeriod reconstitute(
      UUID id, UUID executionId, UUID assignmentId, UUID cabinId, CleaningTrigger trigger,
      Instant startsAt, Instant expectedEndsAt, UUID completedBy, Instant completedAt, int version) {
    CleaningPeriod period = new CleaningPeriod(id, executionId, assignmentId, cabinId, trigger, startsAt, version);
    if (expectedEndsAt == null || !expectedEndsAt.equals(period.expectedEndsAt)) {
      throw new DomainRuleViolationException("INVALID_CLEANING_DEADLINE");
    }
    if (completedBy != null) {
      throw new DomainRuleViolationException("MANUAL_CLEANING_COMPLETION_NOT_ALLOWED");
    }
    if (completedAt != null && !completedAt.equals(period.expectedEndsAt)) {
      throw new DomainRuleViolationException("INVALID_CLEANING_COMPLETION_TIME");
    }
    period.completedAt = completedAt;
    return period;
  }

  public boolean expiredAt(Instant now) {
    if (now == null) throw new DomainRuleViolationException("INVALID_CLEANING_TIME");
    return completedAt == null && !now.isBefore(expectedEndsAt);
  }

  public void completeAutomatically(Instant now) {
    if (completedAt != null) return;
    if (!expiredAt(now)) throw new DomainRuleViolationException("CLEANING_NOT_EXPIRED");
    completedAt = expectedEndsAt;
    version++;
  }

  public UUID cleaningPeriodId() { return cleaningPeriodId; }
  public UUID washExecutionId() { return washExecutionId; }
  public UUID resourceAssignmentId() { return resourceAssignmentId; }
  public UUID cabinId() { return cabinId; }
  public CleaningTrigger cleaningTrigger() { return cleaningTrigger; }
  public Instant startsAt() { return startsAt; }
  public Instant expectedEndsAt() { return expectedEndsAt; }
  public UUID completedByAccountId() { return null; }
  public Instant completedAt() { return completedAt; }
  public int version() { return version; }
  public boolean completed() { return completedAt != null; }
}
