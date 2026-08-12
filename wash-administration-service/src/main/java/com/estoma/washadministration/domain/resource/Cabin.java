package com.estoma.washadministration.domain.resource;

import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.DomainErrorCode;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.ResourceCode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Cabin {

  private final UUID id;
  private final ResourceCode code;
  private final DisplayName name;
  private final CabinType cabinType;
  private final ResourceStatus status;
  private final int version;
  private final UUID createdByAccountId;
  private final UUID updatedByAccountId;
  private final Instant createdAt;
  private final Instant updatedAt;

  private Cabin(
      UUID id,
      ResourceCode code,
      DisplayName name,
      CabinType cabinType,
      ResourceStatus status,
      int version,
      UUID createdByAccountId,
      UUID updatedByAccountId,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.code = Objects.requireNonNull(code, "code");
    this.name = Objects.requireNonNull(name, "name");
    this.cabinType = Objects.requireNonNull(cabinType, "cabinType");
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

  public static Cabin createNew(
      UUID id,
      ResourceCode code,
      DisplayName name,
      CabinType type,
      UUID createdByAccountId,
      Instant now) {
    return new Cabin(id, code, name, type, ResourceStatus.ACTIVE, 1, createdByAccountId, createdByAccountId, now, now);
  }

  public static Cabin reconstitute(
      UUID id,
      ResourceCode code,
      DisplayName name,
      CabinType type,
      ResourceStatus status,
      int version,
      UUID createdByAccountId,
      UUID updatedByAccountId,
      Instant createdAt,
      Instant updatedAt) {
    return new Cabin(id, code, name, type, status, version, createdByAccountId, updatedByAccountId, createdAt, updatedAt);
  }

  public Cabin update(DisplayName newName, CabinType newType, UUID updatedBy, Instant now) {
    ensureNotRetired();
    return copy(newName, newType, status, updatedBy, now);
  }

  public Cabin activate(UUID updatedBy, Instant now) {
    if (status != ResourceStatus.INACTIVE) {
      throw invalidState("only an inactive cabin can be activated");
    }
    return copy(name, cabinType, ResourceStatus.ACTIVE, updatedBy, now);
  }

  public Cabin deactivate(UUID updatedBy, Instant now) {
    if (status != ResourceStatus.ACTIVE) {
      throw invalidState("only an active cabin can be deactivated");
    }
    return copy(name, cabinType, ResourceStatus.INACTIVE, updatedBy, now);
  }

  public Cabin retire(UUID updatedBy, boolean allTanksAreRetired, Instant now) {
    if (status == ResourceStatus.RETIRED) {
      throw invalidState("retired cabin cannot be changed");
    }
    if (!allTanksAreRetired) {
      throw new DomainRuleViolationException(
          DomainErrorCode.CABIN_HAS_NON_RETIRED_TANKS,
          "all tanks must be retired before retiring a cabin");
    }
    return copy(name, cabinType, ResourceStatus.RETIRED, updatedBy, now);
  }

  private Cabin copy(DisplayName newName, CabinType newType, ResourceStatus newStatus, UUID updatedBy, Instant now) {
    return new Cabin(id, code, newName, newType, newStatus, version + 1, createdByAccountId, updatedBy, createdAt, now);
  }

  private void ensureNotRetired() {
    if (status == ResourceStatus.RETIRED) {
      throw invalidState("retired cabin cannot be changed");
    }
  }

  private static DomainRuleViolationException invalidState(String message) {
    return new DomainRuleViolationException(DomainErrorCode.CABIN_INVALID_STATE, message);
  }

  public UUID id() { return id; }
  public ResourceCode code() { return code; }
  public DisplayName name() { return name; }
  public CabinType cabinType() { return cabinType; }
  public ResourceStatus status() { return status; }
  public int version() { return version; }
  public UUID createdByAccountId() { return createdByAccountId; }
  public UUID updatedByAccountId() { return updatedByAccountId; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
}
