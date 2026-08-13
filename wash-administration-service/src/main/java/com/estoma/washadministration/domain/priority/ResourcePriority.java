package com.estoma.washadministration.domain.priority;

import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.domain.resource.ResourceStatus;
import com.estoma.washadministration.domain.shared.DomainErrorCode;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ResourcePriority {

  private final UUID id;
  private final AppointmentType appointmentType;
  private final CabinType cabinType;
  private final UsageMode usageMode;
  private final int priorityOrder;
  private final ResourceStatus status;
  private final int version;
  private final Instant createdAt;
  private final Instant updatedAt;

  private ResourcePriority(
      UUID id,
      AppointmentType appointmentType,
      CabinType cabinType,
      UsageMode usageMode,
      int priorityOrder,
      ResourceStatus status,
      int version,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.appointmentType = Objects.requireNonNull(appointmentType, "appointmentType");
    this.cabinType = Objects.requireNonNull(cabinType, "cabinType");
    this.usageMode = Objects.requireNonNull(usageMode, "usageMode");
    this.priorityOrder = priorityOrder;
    this.status = Objects.requireNonNull(status, "status");
    if (version <= 0 || status != ResourceStatus.ACTIVE) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "resource priority must be active with a positive version");
    }
    this.version = version;
    validateMatrix(appointmentType, cabinType, usageMode, priorityOrder);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static ResourcePriority createNew(
      UUID id,
      AppointmentType appointmentType,
      CabinType cabinType,
      UsageMode usageMode,
      int priorityOrder,
      Instant now) {
    validateMatrix(appointmentType, cabinType, usageMode, priorityOrder);
    return new ResourcePriority(
        id,
        appointmentType,
        cabinType,
        usageMode,
        priorityOrder,
        ResourceStatus.ACTIVE,
        1,
        now,
        now);
  }

  public static ResourcePriority reconstitute(
      UUID id,
      AppointmentType appointmentType,
      CabinType cabinType,
      UsageMode usageMode,
      int priorityOrder,
      ResourceStatus status,
      int version,
      Instant createdAt,
      Instant updatedAt) {
    return new ResourcePriority(
        id,
        appointmentType,
        cabinType,
        usageMode,
        priorityOrder,
        status,
        version,
        createdAt,
        updatedAt);
  }

  private static void validateMatrix(
      AppointmentType appointmentType,
      CabinType cabinType,
      UsageMode usageMode,
      int priorityOrder) {
    boolean valid =
        (appointmentType == AppointmentType.NORMAL
                && cabinType == CabinType.NORMAL
                && usageMode == UsageMode.PREFERRED
                && priorityOrder == 1)
            || (appointmentType == AppointmentType.NORMAL
                && cabinType == CabinType.JOURNEY
                && usageMode == UsageMode.ALTERNATIVE
                && priorityOrder == 2)
            || (appointmentType == AppointmentType.JOURNEY
                && cabinType == CabinType.JOURNEY
                && usageMode == UsageMode.PREFERRED
                && priorityOrder == 1)
            || (appointmentType == AppointmentType.JOURNEY
                && cabinType == CabinType.NORMAL
                && usageMode == UsageMode.ALTERNATIVE
                && priorityOrder == 2)
            || (appointmentType == AppointmentType.IMMUNOCOMPROMISED
                && cabinType == CabinType.IMMUNOCOMPROMISED
                && usageMode == UsageMode.EXCLUSIVE
                && priorityOrder == 1);
    if (!valid) {
      throw new DomainRuleViolationException(
          DomainErrorCode.RESOURCE_PRIORITY_NOT_IN_FIXED_MATRIX,
          "resource priority is not part of the fixed V1 matrix");
    }
  }

  public UUID id() { return id; }
  public AppointmentType appointmentType() { return appointmentType; }
  public CabinType cabinType() { return cabinType; }
  public UsageMode usageMode() { return usageMode; }
  public int priorityOrder() { return priorityOrder; }
  public ResourceStatus status() { return status; }
  public int version() { return version; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
}
