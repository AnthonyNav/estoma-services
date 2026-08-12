package com.estoma.washadministration.domain.resource;

import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.DomainErrorCode;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.PositiveCapacity;
import com.estoma.washadministration.domain.shared.ResourceCode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Tank {

  private final UUID id;
  private final UUID cabinId;
  private final ResourceCode code;
  private final DisplayName name;
  private final PositiveCapacity configuredCapacity;
  private final ResourceStatus status;
  private final int version;
  private final UUID createdByAccountId;
  private final UUID updatedByAccountId;
  private final Instant createdAt;
  private final Instant updatedAt;

  private Tank(
      UUID id,
      UUID cabinId,
      ResourceCode code,
      DisplayName name,
      PositiveCapacity configuredCapacity,
      ResourceStatus status,
      int version,
      UUID createdByAccountId,
      UUID updatedByAccountId,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.cabinId = Objects.requireNonNull(cabinId, "cabinId");
    this.code = Objects.requireNonNull(code, "code");
    this.name = Objects.requireNonNull(name, "name");
    this.configuredCapacity = Objects.requireNonNull(configuredCapacity, "configuredCapacity");
    this.status = Objects.requireNonNull(status, "status");
    if (version <= 0) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "version must be positive");
    }
    this.version = version;
    this.createdByAccountId = Objects.requireNonNull(createdByAccountId, "createdByAccountId");
    this.updatedByAccountId = Objects.requireNonNull(updatedByAccountId, "updatedByAccountId");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static Tank createNew(
      UUID id,
      UUID cabinId,
      ResourceCode code,
      DisplayName name,
      PositiveCapacity configuredCapacity,
      UUID createdByAccountId,
      Instant now) {
    return new Tank(
        id,
        cabinId,
        code,
        name,
        configuredCapacity,
        ResourceStatus.ACTIVE,
        1,
        createdByAccountId,
        createdByAccountId,
        now,
        now);
  }

  public static Tank reconstitute(
      UUID id,
      UUID cabinId,
      ResourceCode code,
      DisplayName name,
      PositiveCapacity configuredCapacity,
      ResourceStatus status,
      int version,
      UUID createdByAccountId,
      UUID updatedByAccountId,
      Instant createdAt,
      Instant updatedAt) {
    return new Tank(
        id,
        cabinId,
        code,
        name,
        configuredCapacity,
        status,
        version,
        createdByAccountId,
        updatedByAccountId,
        createdAt,
        updatedAt);
  }

  public Tank update(DisplayName newName, PositiveCapacity newCapacity, UUID updatedBy, Instant now) {
    ensureNotRetired();
    return copy(newName, newCapacity, status, updatedBy, now);
  }

  public Tank activate(UUID updatedBy, Instant now) {
    if (status != ResourceStatus.INACTIVE) {
      throw invalidState("only an inactive tank can be activated");
    }
    return copy(name, configuredCapacity, ResourceStatus.ACTIVE, updatedBy, now);
  }

  public Tank deactivate(UUID updatedBy, Instant now) {
    if (status != ResourceStatus.ACTIVE) {
      throw invalidState("only an active tank can be deactivated");
    }
    return copy(name, configuredCapacity, ResourceStatus.INACTIVE, updatedBy, now);
  }

  public Tank retire(UUID updatedBy, Instant now) {
    if (status == ResourceStatus.RETIRED) {
      throw invalidState("retired tank cannot be changed");
    }
    return copy(name, configuredCapacity, ResourceStatus.RETIRED, updatedBy, now);
  }

  private Tank copy(
      DisplayName newName,
      PositiveCapacity newCapacity,
      ResourceStatus newStatus,
      UUID updatedBy,
      Instant now) {
    return new Tank(
        id,
        cabinId,
        code,
        newName,
        newCapacity,
        newStatus,
        version + 1,
        createdByAccountId,
        updatedBy,
        createdAt,
        now);
  }

  private void ensureNotRetired() {
    if (status == ResourceStatus.RETIRED) {
      throw invalidState("retired tank cannot be changed");
    }
  }

  private static DomainRuleViolationException invalidState(String message) {
    return new DomainRuleViolationException(DomainErrorCode.TANK_INVALID_STATE, message);
  }

  public UUID id() { return id; }
  public UUID cabinId() { return cabinId; }
  public ResourceCode code() { return code; }
  public DisplayName name() { return name; }
  public PositiveCapacity configuredCapacity() { return configuredCapacity; }
  public ResourceStatus status() { return status; }
  public int version() { return version; }
  public UUID createdByAccountId() { return createdByAccountId; }
  public UUID updatedByAccountId() { return updatedByAccountId; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
}
