package com.estoma.authorization.domain.model;

import com.estoma.authorization.domain.exception.DomainErrorCode;
import com.estoma.authorization.domain.exception.DomainRuleViolationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The single current role assignment owned by Authorization for an account. */
public final class AccountRoleAssignment {

  private final UUID accountId;
  private final UUID roleId;
  private final RoleCode roleCode;
  private final int version;
  private final ActorRef assignedBy;
  private final ActorRef changedBy;
  private final Instant assignedAt;
  private final Instant updatedAt;

  private AccountRoleAssignment(
      UUID accountId,
      UUID roleId,
      RoleCode roleCode,
      int version,
      ActorRef assignedBy,
      ActorRef changedBy,
      Instant assignedAt,
      Instant updatedAt) {
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.roleId = Objects.requireNonNull(roleId, "roleId");
    this.roleCode = Objects.requireNonNull(roleCode, "roleCode");
    if (version <= 0) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "version must be positive");
    }
    if (version > 1 && changedBy == null) {
      throw new DomainRuleViolationException(
          DomainErrorCode.INVALID_STATE, "changed role assignments require an actor");
    }
    this.version = version;
    this.assignedBy = Objects.requireNonNull(assignedBy, "assignedBy");
    this.changedBy = changedBy;
    this.assignedAt = Objects.requireNonNull(assignedAt, "assignedAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(assignedAt)) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "updatedAt precedes assignedAt");
    }
  }

  public static AccountRoleAssignment createNew(
      UUID accountId,
      UUID roleId,
      RoleCode roleCode,
      ActorRef assignedBy,
      Instant now) {
    return new AccountRoleAssignment(
        accountId, roleId, roleCode, 1, assignedBy, null, now, now);
  }

  public static AccountRoleAssignment reconstitute(
      UUID accountId,
      UUID roleId,
      RoleCode roleCode,
      int version,
      ActorRef assignedBy,
      ActorRef changedBy,
      Instant assignedAt,
      Instant updatedAt) {
    return new AccountRoleAssignment(
        accountId,
        roleId,
        roleCode,
        version,
        assignedBy,
        changedBy,
        assignedAt,
        updatedAt);
  }

  public UUID accountId() { return accountId; }
  public UUID roleId() { return roleId; }
  public RoleCode roleCode() { return roleCode; }
  public int version() { return version; }
  public ActorRef assignedBy() { return assignedBy; }
  public ActorRef changedBy() { return changedBy; }
  public Instant assignedAt() { return assignedAt; }
  public Instant updatedAt() { return updatedAt; }
}
