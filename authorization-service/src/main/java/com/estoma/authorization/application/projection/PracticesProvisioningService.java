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
      return;
    }

    // Existence is required for administrative provisioning even though ACTIVE status is not.
    var account = accounts.findByAccountId(accountId);
    if (account.isEmpty()
        || recoveryJobs.hasActiveFull("ACCOUNT_STATUS")
        || recoveryJobs.hasActiveFull("ACADEMIC_ELIGIBILITY")
        || inbox.hasDeferredNewerThan(
            EVENT_CONSUMER, accountId, account.get().sourceVersion())) {
      return;
    }

    Optional<AccountRoleAssignment> roleAssignment = accountRoles.findByAccountId(accountId);
    if (roleAssignment.isEmpty() || roleAssignment.get().roleCode() != RoleCode.ALUMNO) {
      return;
    }
    var role = roles.findById(roleAssignment.get().roleId());
    var system = systems.findByCode(SystemCode.PRACTICAS_PROFESIONALES.name());
    if (role.isEmpty()
        || role.get().status() != CatalogStatus.ACTIVE
        || system.isEmpty()
        || system.get().status() != CatalogStatus.ACTIVE
        || !roles.isAllowedInSystem(roleAssignment.get().roleId(), system.get().id())) {
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
      // A concurrent trigger won the unique(account_id, system_id) race. The converged state is satisfied.
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
