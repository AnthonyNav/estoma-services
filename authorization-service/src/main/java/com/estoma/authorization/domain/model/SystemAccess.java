package com.estoma.authorization.domain.model;

import com.estoma.authorization.domain.exception.DomainErrorCode;
import com.estoma.authorization.domain.exception.DomainRuleViolationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Lifecycle state for one account/system relationship. */
public final class SystemAccess {

  private final UUID systemAccessId;
  private final UUID accountId;
  private final UUID systemId;
  private final SystemCode systemCode;
  private final SystemAccessStatus status;
  private final SystemAccessReasonCode reasonCode;
  private final String reasonDetail;
  private final int version;
  private final ActorRef grantedBy;
  private final ActorRef suspendedBy;
  private final ActorRef revokedBy;
  private final ActorRef restoredBy;
  private final Instant grantedAt;
  private final Instant suspendedAt;
  private final Instant revokedAt;
  private final Instant updatedAt;

  private SystemAccess(
      UUID systemAccessId,
      UUID accountId,
      UUID systemId,
      SystemCode systemCode,
      SystemAccessStatus status,
      SystemAccessReasonCode reasonCode,
      String reasonDetail,
      int version,
      ActorRef grantedBy,
      ActorRef suspendedBy,
      ActorRef revokedBy,
      ActorRef restoredBy,
      Instant grantedAt,
      Instant suspendedAt,
      Instant revokedAt,
      Instant updatedAt) {
    this.systemAccessId = Objects.requireNonNull(systemAccessId, "systemAccessId");
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.systemId = Objects.requireNonNull(systemId, "systemId");
    this.systemCode = Objects.requireNonNull(systemCode, "systemCode");
    this.status = Objects.requireNonNull(status, "status");
    this.reasonCode = reasonCode;
    this.reasonDetail = reasonDetail;
    if (version <= 0) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "version must be positive");
    }
    validateReason(status, reasonCode, reasonDetail);
    if (status == SystemAccessStatus.SUSPENDED && (suspendedBy == null || suspendedAt == null)) {
      throw new DomainRuleViolationException(
          DomainErrorCode.INVALID_STATE, "suspended access requires transition attribution");
    }
    if (status == SystemAccessStatus.REVOKED && (revokedBy == null || revokedAt == null)) {
      throw new DomainRuleViolationException(
          DomainErrorCode.INVALID_STATE, "revoked access requires transition attribution");
    }
    if (status == SystemAccessStatus.ACTIVE && version > 1 && restoredBy == null) {
      throw new DomainRuleViolationException(
          DomainErrorCode.INVALID_STATE, "restored access requires transition attribution");
    }
    this.version = version;
    this.grantedBy = Objects.requireNonNull(grantedBy, "grantedBy");
    this.suspendedBy = suspendedBy;
    this.revokedBy = revokedBy;
    this.restoredBy = restoredBy;
    this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
    this.suspendedAt = suspendedAt;
    this.revokedAt = revokedAt;
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static SystemAccess createNew(
      UUID systemAccessId,
      UUID accountId,
      UUID systemId,
      SystemCode systemCode,
      ActorRef grantedBy,
      Instant now) {
    return new SystemAccess(
        systemAccessId,
        accountId,
        systemId,
        systemCode,
        SystemAccessStatus.ACTIVE,
        null,
        null,
        1,
        grantedBy,
        null,
        null,
        null,
        now,
        null,
        null,
        now);
  }

  public static SystemAccess reconstitute(
      UUID systemAccessId,
      UUID accountId,
      UUID systemId,
      SystemCode systemCode,
      SystemAccessStatus status,
      SystemAccessReasonCode reasonCode,
      String reasonDetail,
      int version,
      ActorRef grantedBy,
      ActorRef suspendedBy,
      ActorRef revokedBy,
      ActorRef restoredBy,
      Instant grantedAt,
      Instant suspendedAt,
      Instant revokedAt,
      Instant updatedAt) {
    return new SystemAccess(
        systemAccessId,
        accountId,
        systemId,
        systemCode,
        status,
        reasonCode,
        reasonDetail,
        version,
        grantedBy,
        suspendedBy,
        revokedBy,
        restoredBy,
        grantedAt,
        suspendedAt,
        revokedAt,
        updatedAt);
  }

  public SystemAccess suspend(
      SystemAccessReasonCode reason, String detail, ActorRef actor, Instant now) {
    Objects.requireNonNull(actor, "actor");
    if (status != SystemAccessStatus.ACTIVE) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_TRANSITION, "only ACTIVE access can suspend");
    }
    validateReason(SystemAccessStatus.SUSPENDED, reason, detail);
    return copy(
        SystemAccessStatus.SUSPENDED,
        reason,
        detail,
        now,
        now,
        revokedAt,
        grantedBy,
        actor,
        revokedBy,
        restoredBy);
  }

  public SystemAccess revoke(
      SystemAccessReasonCode reason, String detail, ActorRef actor, Instant now) {
    Objects.requireNonNull(actor, "actor");
    if (status == SystemAccessStatus.REVOKED) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_TRANSITION, "access is already revoked");
    }
    validateReason(SystemAccessStatus.REVOKED, reason, detail);
    return copy(
        SystemAccessStatus.REVOKED,
        reason,
        detail,
        now,
        suspendedAt,
        now,
        grantedBy,
        suspendedBy,
        actor,
        restoredBy);
  }

  public SystemAccess restore(ActorRef actor, Instant now) {
    Objects.requireNonNull(actor, "actor");
    if (status == SystemAccessStatus.ACTIVE) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_TRANSITION, "access is already active");
    }
    return copy(
        SystemAccessStatus.ACTIVE,
        null,
        null,
        now,
        suspendedAt,
        revokedAt,
        grantedBy,
        suspendedBy,
        revokedBy,
        actor);
  }

  private SystemAccess copy(
      SystemAccessStatus newStatus,
      SystemAccessReasonCode newReason,
      String newDetail,
      Instant now,
      Instant newSuspendedAt,
      Instant newRevokedAt,
      ActorRef newGrantedBy,
      ActorRef newSuspendedBy,
      ActorRef newRevokedBy,
      ActorRef newRestoredBy) {
    return new SystemAccess(
        systemAccessId,
        accountId,
        systemId,
        systemCode,
        newStatus,
        newReason,
        newDetail,
        version + 1,
        newGrantedBy,
        newSuspendedBy,
        newRevokedBy,
        newRestoredBy,
        grantedAt,
        newSuspendedAt,
        newRevokedAt,
        now);
  }

  private static void validateReason(
      SystemAccessStatus status, SystemAccessReasonCode reason, String detail) {
    if (status == SystemAccessStatus.ACTIVE && (reason != null || detail != null)) {
      throw new DomainRuleViolationException(DomainErrorCode.REASON_REQUIRED, "ACTIVE access cannot have a reason");
    }
    if (status != SystemAccessStatus.ACTIVE && reason == null) {
      throw new DomainRuleViolationException(DomainErrorCode.REASON_REQUIRED, "non-active access requires a reason");
    }
    if (reason == SystemAccessReasonCode.OTHER && (detail == null || detail.isBlank())) {
      throw new DomainRuleViolationException(DomainErrorCode.REASON_REQUIRED, "OTHER requires reason detail");
    }
    if (reason == SystemAccessReasonCode.ROLE_CHANGED && status != SystemAccessStatus.SUSPENDED) {
      throw new DomainRuleViolationException(DomainErrorCode.INVALID_STATE, "ROLE_CHANGED only suspends access");
    }
  }

  public UUID systemAccessId() { return systemAccessId; }
  public UUID accountId() { return accountId; }
  public UUID systemId() { return systemId; }
  public SystemCode systemCode() { return systemCode; }
  public SystemAccessStatus status() { return status; }
  public SystemAccessReasonCode reasonCode() { return reasonCode; }
  public String reasonDetail() { return reasonDetail; }
  public int version() { return version; }
  public ActorRef grantedBy() { return grantedBy; }
  public ActorRef suspendedBy() { return suspendedBy; }
  public ActorRef revokedBy() { return revokedBy; }
  public ActorRef restoredBy() { return restoredBy; }
  public Instant grantedAt() { return grantedAt; }
  public Instant suspendedAt() { return suspendedAt; }
  public Instant revokedAt() { return revokedAt; }
  public Instant updatedAt() { return updatedAt; }
}
