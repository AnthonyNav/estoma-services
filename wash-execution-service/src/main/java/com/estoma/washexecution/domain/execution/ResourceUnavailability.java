package com.estoma.washexecution.domain.execution;

import com.estoma.washexecution.domain.shared.DomainRuleViolationException;
import java.time.Instant;
import java.util.UUID;

public final class ResourceUnavailability {
  private final UUID resourceUnavailabilityId;
  private final UUID detectedDuringWashExecutionId;
  private final UUID cabinId;
  private final UUID tankId;
  private final UnavailabilityCauseType causeType;
  private final String reason;
  private final UUID disabledByAccountId;
  private final Instant disabledAt;
  private UUID restoredByAccountId;
  private Instant restoredAt;
  private String resolution;
  private int version;

  public ResourceUnavailability(
      UUID id, UUID detectedDuring, UUID cabinId, UUID tankId, UnavailabilityCauseType causeType,
      String reason, UUID disabledBy, Instant disabledAt, int version) {
    if ((cabinId == null) == (tankId == null)) {
      throw new DomainRuleViolationException("EXACTLY_ONE_RESOURCE_REQUIRED");
    }
    if (id == null || causeType == null || reason == null || reason.isBlank()
        || disabledBy == null || disabledAt == null || version < 1) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_UNAVAILABILITY");
    }
    this.resourceUnavailabilityId = id;
    this.detectedDuringWashExecutionId = detectedDuring;
    this.cabinId = cabinId;
    this.tankId = tankId;
    this.causeType = causeType;
    this.reason = reason;
    this.disabledByAccountId = disabledBy;
    this.disabledAt = disabledAt;
    this.version = version;
  }

  public static ResourceUnavailability reconstitute(
      UUID id, UUID detectedDuring, UUID cabinId, UUID tankId, UnavailabilityCauseType causeType,
      String reason, UUID disabledBy, Instant disabledAt, UUID restoredBy, Instant restoredAt,
      String resolution, int version) {
    ResourceUnavailability value = new ResourceUnavailability(id, detectedDuring, cabinId, tankId,
        causeType, reason, disabledBy, disabledAt, version);
    if (restoredAt == null && (restoredBy != null || resolution != null)) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_RESTORE_STATE");
    }
    if (restoredAt != null && (restoredBy == null || restoredAt.isBefore(disabledAt))) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_RESTORE_STATE");
    }
    value.restoredByAccountId = restoredBy;
    value.restoredAt = restoredAt;
    value.resolution = resolution;
    return value;
  }

  public void restore(UUID accountId, Instant at, String resolution) {
    if (restoredAt != null) {
      throw new DomainRuleViolationException("RESOURCE_ALREADY_RESTORED");
    }
    if (accountId == null || at == null || at.isBefore(disabledAt)) {
      throw new DomainRuleViolationException("INVALID_RESOURCE_RESTORE");
    }
    restoredByAccountId = accountId;
    restoredAt = at;
    this.resolution = resolution == null || resolution.isBlank() ? null : resolution;
    version++;
  }

  public UUID resourceUnavailabilityId() { return resourceUnavailabilityId; }
  public UUID detectedDuringWashExecutionId() { return detectedDuringWashExecutionId; }
  public UUID cabinId() { return cabinId; }
  public UUID tankId() { return tankId; }
  public UnavailabilityCauseType causeType() { return causeType; }
  public String reason() { return reason; }
  public UUID disabledByAccountId() { return disabledByAccountId; }
  public Instant disabledAt() { return disabledAt; }
  public UUID restoredByAccountId() { return restoredByAccountId; }
  public Instant restoredAt() { return restoredAt; }
  public String resolution() { return resolution; }
  public int version() { return version; }
  public boolean active() { return restoredAt == null; }
}
