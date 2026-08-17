package com.estoma.authorization.application.projection;

import com.estoma.authorization.application.command.DomainEventIntent;
import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.RoleCatalogRepository;
import com.estoma.authorization.application.port.SystemAccessRepository;
import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.application.support.CanonicalJson;
import com.estoma.authorization.application.support.OutboxIntentWriter;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PracticesProvisioningService {

  private static final String EVENT_CONSUMER = "authorization-events";

  private final AcademicEligibilityProjectionRepository eligibility;
  private final AccountStatusProjectionRepository accounts;
  private final AccountRoleRepository accountRoles;
  private final RoleCatalogRepository roles;
  private final SystemCatalogRepository systems;
  private final SystemAccessRepository accesses;
  private final ProcessedMessageRepository inbox;
  private final ProjectionRecoveryJobRepository recoveryJobs;
  private final ProjectionRecoveryCoordinator recovery;
  private final OutboxRepository outbox;
  private final AuthorizationAuditRepository audit;

  public PracticesProvisioningService(
      AcademicEligibilityProjectionRepository eligibility,
      AccountStatusProjectionRepository accounts,
      AccountRoleRepository accountRoles,
      RoleCatalogRepository roles,
      SystemCatalogRepository systems,
      SystemAccessRepository accesses,
      ProcessedMessageRepository inbox,
      ProjectionRecoveryJobRepository recoveryJobs,
      ProjectionRecoveryCoordinator recovery,
      OutboxRepository outbox,
      AuthorizationAuditRepository audit) {
    this.eligibility = eligibility;
    this.accounts = accounts;
    this.accountRoles = accountRoles;
    this.roles = roles;
    this.systems = systems;
    this.accesses = accesses;
    this.inbox = inbox;
    this.recoveryJobs = recoveryJobs;
    this.recovery = recovery;
    this.outbox = outbox;
    this.audit = audit;
  }

  public void evaluate(UUID accountId, Trigger trigger) {
    var currentEligibility = eligibility.findByAccountIdAndSystemCode(
        accountId, SystemCode.PRACTICAS_PROFESIONALES.name());
    if (currentEligibility.isEmpty()) {
      return;
    }
    var eligibilityState = currentEligibility.get();
    if (!eligibilityState.eligible() || eligibilityState.policyVersion() != 1) {
      clearPendingReason(eligibilityState);
      return;
    }

    String accountKey = "account:" + accountId;
    String eligibilityKey =
        "eligibility:" + eligibilityState.studentAcademicProfileId() + ":" + eligibilityState.systemCode();

    if (!recoveryJobs.isProjectionTrusted("ACADEMIC_ELIGIBILITY")
        || !recoveryJobs.isEntityTrusted("ACADEMIC_ELIGIBILITY", eligibilityKey)) {
      setPendingReason(eligibilityState, "PROJECTION_RECONCILIATION", trigger);
      return;
    }

    var account = accounts.findByAccountId(accountId);
    if (account.isEmpty()) {
      boolean accountEventPending = inbox.findDeferredByAggregate(EVENT_CONSUMER, accountId, 20).stream()
          .anyMatch(message -> "authentication".equals(message.producer())
              && "AccountCreated".equals(message.messageType()));
      if (accountEventPending) {
        setPendingReason(eligibilityState, "EVENT_PENDING", trigger);
        return;
      }
      if (accountRoles.findByAccountId(accountId).isPresent()
          || !recoveryJobs.isEntityTrusted("ACCOUNT_STATUS", accountKey)) {
        setPendingReason(eligibilityState, "PROJECTION_RECONCILIATION", trigger);
        recovery.requestAccount(accountId);
      }
      return;
    }

    if (!recoveryJobs.isProjectionTrusted("ACCOUNT_STATUS")
        || !recoveryJobs.isEntityTrusted("ACCOUNT_STATUS", accountKey)) {
      setPendingReason(eligibilityState, "PROJECTION_RECONCILIATION", trigger);
      recovery.requestAccount(accountId);
      return;
    }
    if (inbox.hasDeferredNewerThan(
        EVENT_CONSUMER, accountId, account.get().sourceVersion())) {
      setPendingReason(eligibilityState, "EVENT_PENDING", trigger);
      return;
    }

    Optional<AccountRoleAssignment> roleAssignment = accountRoles.findByAccountId(accountId);
    if (roleAssignment.isEmpty() || roleAssignment.get().roleCode() != RoleCode.ALUMNO) {
      clearPendingReason(eligibilityState);
      return;
    }
    var role = roles.findById(roleAssignment.get().roleId());
    var system = systems.findByCode(SystemCode.PRACTICAS_PROFESIONALES.name());
    if (role.isEmpty()
        || role.get().status() != CatalogStatus.ACTIVE
        || system.isEmpty()
        || system.get().status() != CatalogStatus.ACTIVE
        || !roles.isAllowedInSystem(roleAssignment.get().roleId(), system.get().id())) {
      clearPendingReason(eligibilityState);
      return;
    }
    Optional<SystemAccess> existing = accesses.findByAccountIdAndSystemId(accountId, system.get().id());
    if (existing.isPresent()) {
      clearPendingReason(eligibilityState);
      return;
    }

    ActorRef actor = ActorRef.system("authorization");
    SystemAccess access = SystemAccess.createNew(
        UUID.randomUUID(),
        accountId,
        system.get().id(),
        SystemCode.PRACTICAS_PROFESIONALES,
        actor,
        trigger.occurredAt());
    if (!accesses.insertIfAbsent(access)) {
      clearPendingReason(eligibilityState);
      return;
    }
    Map<String, Object> eventPayload = new LinkedHashMap<>();
    eventPayload.put("systemAccessId", access.systemAccessId());
    eventPayload.put("accountId", accountId);
    eventPayload.put("systemId", access.systemId());
    eventPayload.put("systemCode", access.systemCode());
    eventPayload.put("roleId", roleAssignment.get().roleId());
    eventPayload.put("roleCode", roleAssignment.get().roleCode());
    eventPayload.put("status", access.status());
    eventPayload.put("grantedAt", access.grantedAt());
    DomainEventIntent event = new DomainEventIntent(
        UUID.randomUUID(),
        "SystemAccessGranted",
        1,
        "authorization",
        "authorization.system-access.granted",
        trigger.operationId(),
        trigger.idempotencyKey(),
        actor,
        access.systemAccessId(),
        access.version(),
        trigger.correlationId(),
        trigger.messageId(),
        CanonicalJson.write(eventPayload),
        trigger.occurredAt());
    OutboxIntentWriter.append(outbox, event);
    audit.append(new AuthorizationAuditRepository.AuditEntry(
        UUID.randomUUID(),
        trigger.messageId(),
        trigger.sourceProducer(),
        "SystemAccess",
        access.systemAccessId(),
        access.version(),
        "DERIVED_SYSTEM_ACCESS_GRANTED",
        actor,
        trigger.businessInitiatorAccountId(),
        trigger.businessImportId(),
        trigger.correlationId(),
        trigger.causationId(),
        trigger.operationId(),
        trigger.idempotencyKey(),
        "SUCCEEDED",
        null,
        null,
        trigger.occurredAt()));
    clearPendingReason(eligibilityState);
  }

  private void setPendingReason(
      AcademicEligibilityProjectionRepository.AcademicEligibilityProjection eligibilityState,
      String pendingReason,
      Trigger trigger) {
    if (pendingReason.equals(eligibilityState.pendingReason())) {
      return;
    }
    eligibility.updatePendingReason(
        eligibilityState.studentAcademicProfileId(), eligibilityState.systemCode(), pendingReason);
    if ("PROJECTION_RECONCILIATION".equals(pendingReason)) {
      audit.append(new AuthorizationAuditRepository.AuditEntry(
          UUID.randomUUID(),
          trigger.messageId(),
          trigger.sourceProducer(),
          "AcademicEligibility",
          eligibilityState.eligibilityId(),
          eligibilityState.eligibilityVersion(),
          "ELIGIBILITY_RECONCILIATION_REQUESTED",
          ActorRef.system("authorization"),
          trigger.businessInitiatorAccountId(),
          trigger.businessImportId(),
          trigger.correlationId(),
          trigger.causationId(),
          trigger.operationId(),
          trigger.idempotencyKey(),
          "DEFERRED",
          "PROJECTION_RECONCILIATION",
          null,
          trigger.occurredAt()));
    }
  }

  private void clearPendingReason(
      AcademicEligibilityProjectionRepository.AcademicEligibilityProjection eligibilityState) {
    if (eligibilityState.pendingReason() != null) {
      eligibility.updatePendingReason(
          eligibilityState.studentAcademicProfileId(), eligibilityState.systemCode(), null);
    }
  }

  public record Trigger(
      UUID messageId,
      String sourceProducer,
      UUID correlationId,
      UUID causationId,
      UUID operationId,
      String idempotencyKey,
      UUID businessInitiatorAccountId,
      UUID businessImportId,
      Instant occurredAt) {}
}
