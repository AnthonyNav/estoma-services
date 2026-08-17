package com.estoma.authorization.application.command;

import com.estoma.authorization.application.authorization.AuthorityEvaluator;
import com.estoma.authorization.application.exception.AuthorizationErrorCode;
import com.estoma.authorization.application.exception.AuthorizationRuleViolationException;
import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.CommandIdempotencyRepository.IdempotencyRecord;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.RoleCatalogRepository;
import com.estoma.authorization.application.port.SystemAccessRepository;
import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.projection.PracticesProvisioningService;
import com.estoma.authorization.application.support.CanonicalJson;
import com.estoma.authorization.application.support.OutboxIntentWriter;
import com.estoma.authorization.application.support.ResultIntentFactory;
import com.estoma.authorization.domain.exception.DomainRuleViolationException;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.RoleCatalog;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemAccessReasonCode;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCatalog;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AuthorizationCommandDispatcher implements CommandDispatcher {

  private static final String COMMAND_CONSUMER = "authorization-commands";
  private final TransactionRunner transactions;
  private final AccountRoleRepository accountRoles;
  private final AccountStatusProjectionRepository accounts;
  private final AcademicEligibilityProjectionRepository eligibility;
  private final AuthorizationAuditRepository audit;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final PersonStatusProjectionRepository persons;
  private final ProcessedMessageRepository inbox;
  private final ProjectionRecoveryJobRepository recoveryJobs;
  private final RoleCatalogRepository roles;
  private final SystemAccessRepository accesses;
  private final SystemCatalogRepository systems;
  private final AuthorityEvaluator authority;
  private final PracticesProvisioningService provisioning;
  private final Clock clock;

  public AuthorizationCommandDispatcher(
      TransactionRunner transactions,
      AccountRoleRepository accountRoles,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      AuthorizationAuditRepository audit,
      CommandIdempotencyRepository idempotency,
      OutboxRepository outbox,
      PersonStatusProjectionRepository persons,
      ProcessedMessageRepository inbox,
      ProjectionRecoveryJobRepository recoveryJobs,
      RoleCatalogRepository roles,
      SystemAccessRepository accesses,
      SystemCatalogRepository systems,
      AuthorityEvaluator authority,
      PracticesProvisioningService provisioning,
      Clock clock) {
    this.transactions = transactions;
    this.accountRoles = accountRoles;
    this.accounts = accounts;
    this.eligibility = eligibility;
    this.audit = audit;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.persons = persons;
    this.inbox = inbox;
    this.recoveryJobs = recoveryJobs;
    this.roles = roles;
    this.accesses = accesses;
    this.systems = systems;
    this.authority = authority;
    this.provisioning = provisioning;
    this.clock = clock;
  }

  @Override
  public CommandOutcome dispatch(CommandMetadata metadata, AuthorizationCommand command) {
    validateEnvelope(metadata, command);
    return transactions.inTransaction(() -> dispatchInTransaction(metadata, command));
  }

  /** Re-evaluates the original durable Academic DEFERRED intention. */
  public CommandOutcome replayDeferred(CommandMetadata metadata, AuthorizationCommand command) {
    validateEnvelope(metadata, command);
    return transactions.inTransaction(() -> replayDeferredInTransaction(metadata, command));
  }

  private CommandOutcome replayDeferredInTransaction(
      CommandMetadata metadata, AuthorizationCommand command) {
    Instant now = clock.instant();
    String fingerprint = CommandPayloads.fingerprint(metadata, command);
    var delivery = inbox.find(COMMAND_CONSUMER, metadata.messageId())
        .orElseThrow(() -> new IllegalStateException("deferred command Inbox entry is missing"));
    if ("APPLIED".equals(delivery.status())) {
      return CommandOutcome.duplicate();
    }
    IdempotencyRecord record = idempotency.find(
            metadata.producer(), command.operationType(), metadata.idempotencyKey())
        .orElseThrow(() -> new IllegalStateException("deferred command idempotency entry is missing"));
    if (!record.requestFingerprint().equals(fingerprint)) {
      throw new IllegalStateException("deferred command fingerprint no longer matches durable intent");
    }
    if (!"DEFERRED".equals(record.status())) {
      inbox.markApplied(COMMAND_CONSUMER, metadata.messageId(), now, now);
      return CommandOutcome.duplicate();
    }
    if (!"academic".equals(metadata.producer()) || command.importExecution() == null) {
      throw new IllegalStateException("only coordinated Academic commands can be deferred in V1");
    }

    if (isStaleAcademicGeneration(metadata, command)) {
      return finalizeTerminal(
          metadata,
          command,
          fingerprint,
          HandledCommand.rejected(ApplicationResult.rejected(
              AuthorizationErrorCode.STALE_EXECUTION_GENERATION.name(),
              "deferred execution generation is older than the latest observed generation")),
          now);
    }

    try {
      Optional<SystemAccess> targetAccess = targetAccess(command);
      Optional<AccountRoleAssignment> targetRole = command.targetAccountId() == null
          ? Optional.empty()
          : accountRoles.findByAccountId(command.targetAccountId());
      if (targetRole.isEmpty() && targetAccess.isPresent()) {
        targetRole = accountRoles.findByAccountId(targetAccess.get().accountId());
      }
      authority.authorize(metadata, command, targetRole, targetAccess);
      HandledCommand handled = execute(metadata, command, targetRole, targetAccess, now);
      if (handled.deferred()) {
        return finalizeDeferred(metadata, command, fingerprint, handled.pendingReason(), now);
      }
      return finalizeTerminal(metadata, command, fingerprint, handled, now);
    } catch (AuthorizationRuleViolationException exception) {
      return finalizeTerminal(
          metadata,
          command,
          fingerprint,
          HandledCommand.rejected(
              ApplicationResult.rejected(exception.code().name(), exception.getMessage())),
          now);
    } catch (DomainRuleViolationException exception) {
      AuthorizationErrorCode code = exception.code().name().equals("REASON_REQUIRED")
          ? AuthorizationErrorCode.REASON_DETAIL_REQUIRED
          : AuthorizationErrorCode.INVALID_COMMAND;
      return finalizeTerminal(
          metadata,
          command,
          fingerprint,
          HandledCommand.rejected(ApplicationResult.rejected(code.name(), exception.getMessage())),
          now);
    }
  }

  private CommandOutcome dispatchInTransaction(CommandMetadata metadata, AuthorizationCommand command) {
    Instant now = clock.instant();
    String payloadJson = CommandPayloads.write(command);
    String fingerprint = CommandPayloads.fingerprint(metadata, command);
    boolean newDelivery = ensureInbox(metadata, command, payloadJson, now);

    Optional<IdempotencyRecord> existing = idempotency.find(
        metadata.producer(), command.operationType(), metadata.idempotencyKey());
    if (existing.isPresent()) {
      return handleExistingIdempotency(metadata, command, fingerprint, existing.get(), now, newDelivery);
    }

    IdempotencyRecord initial = initialIdempotencyRecord(metadata, command, fingerprint, now);
    if (!idempotency.insertIfAbsent(initial)) {
      return idempotency.find(metadata.producer(), command.operationType(), metadata.idempotencyKey())
          .map(record -> handleExistingIdempotency(metadata, command, fingerprint, record, now, newDelivery))
          .orElseThrow(() -> new IllegalStateException("idempotency reservation disappeared"));
    }

    if (isStaleAcademicGeneration(metadata, command)) {
      return finalizeTerminal(
          metadata,
          command,
          fingerprint,
          HandledCommand.rejected(ApplicationResult.rejected(
              AuthorizationErrorCode.STALE_EXECUTION_GENERATION.name(),
              "execution generation is older than the latest observed generation")),
          now);
    }

    try {
      Optional<SystemAccess> targetAccess = targetAccess(command);
      Optional<AccountRoleAssignment> targetRole = command.targetAccountId() == null
          ? Optional.empty()
          : accountRoles.findByAccountId(command.targetAccountId());
      if (targetRole.isEmpty() && targetAccess.isPresent()) {
        targetRole = accountRoles.findByAccountId(targetAccess.get().accountId());
      }
      authority.authorize(metadata, command, targetRole, targetAccess);
      HandledCommand handled = execute(metadata, command, targetRole, targetAccess, now);
      if (handled.deferred()) {
        return finalizeDeferred(metadata, command, fingerprint, handled.pendingReason(), now);
      }
      return finalizeTerminal(metadata, command, fingerprint, handled, now);
    } catch (AuthorizationRuleViolationException exception) {
      return finalizeTerminal(
          metadata,
          command,
          fingerprint,
          HandledCommand.rejected(ApplicationResult.rejected(exception.code().name(), exception.getMessage())),
          now);
    } catch (DomainRuleViolationException exception) {
      AuthorizationErrorCode code = exception.code().name().equals("REASON_REQUIRED")
          ? AuthorizationErrorCode.REASON_DETAIL_REQUIRED
          : AuthorizationErrorCode.INVALID_COMMAND;
      return finalizeTerminal(
          metadata,
          command,
          fingerprint,
          HandledCommand.rejected(ApplicationResult.rejected(code.name(), exception.getMessage())),
          now);
    }
  }

  private boolean isStaleAcademicGeneration(
      CommandMetadata metadata, AuthorizationCommand command) {
    ImportExecution execution = command.importExecution();
    if (!"academic".equals(metadata.producer()) || execution == null) {
      return false;
    }
    return idempotency.findLatestExecutionGeneration(
            metadata.producer(), execution.importId(), execution.planItemId())
        .map(latest -> execution.executionGeneration() < latest)
        .orElse(false);
  }

  private void validateEnvelope(CommandMetadata metadata, AuthorizationCommand command) {
    if (!command.operationType().equals(metadata.messageType())) {
      throw new AuthorizationRuleViolationException(
          AuthorizationErrorCode.INVALID_COMMAND, "messageType does not match command");
    }
    if ("academic".equals(metadata.producer()) && command.importExecution() == null) {
      throw new AuthorizationRuleViolationException(
          AuthorizationErrorCode.ACTOR_CONTEXT_INVALID, "Academic command requires importExecution");
    }
    if (!"academic".equals(metadata.producer()) && command.importExecution() != null) {
      throw new AuthorizationRuleViolationException(
          AuthorizationErrorCode.ACTOR_CONTEXT_INVALID, "importExecution is restricted to Academic");
    }
  }

  private boolean ensureInbox(
      CommandMetadata metadata, AuthorizationCommand command, String payloadJson, Instant now) {
    return inbox.insertIfAbsent(new ProcessedMessageRepository.ProcessedMessage(
        COMMAND_CONSUMER,
        metadata.messageId(),
        "DEFERRED",
        metadata.messageType(),
        metadata.messageVersion(),
        "COMMAND",
        metadata.producer(),
        metadata.correlationId(),
        metadata.causationId(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        metadata.aggregateId(),
        metadata.aggregateVersion(),
        metadata.actor(),
        payloadJson,
        now,
        metadata.occurredAt(),
        null,
        "PROCESSING",
        now));
  }

  private IdempotencyRecord initialIdempotencyRecord(
      CommandMetadata metadata, AuthorizationCommand command, String fingerprint, Instant now) {
    ImportExecution execution = command.importExecution();
    return new IdempotencyRecord(
        metadata.producer(),
        command.operationType(),
        metadata.idempotencyKey(),
        fingerprint,
        "DEFERRED",
        null,
        null,
        null,
        null,
        null,
        null,
        execution == null ? null : execution.importId(),
        execution == null ? null : execution.planItemId(),
        execution == null ? null : execution.executionGeneration(),
        execution == null ? null : "EVENT_PENDING",
        now,
        null,
        now);
  }

  private CommandOutcome handleExistingIdempotency(
      CommandMetadata metadata,
      AuthorizationCommand command,
      String fingerprint,
      IdempotencyRecord existing,
      Instant now,
      boolean newDelivery) {
    if (!existing.requestFingerprint().equals(fingerprint)) {
      return finalizeStandaloneRejection(
          metadata,
          command,
          ApplicationResult.rejected(
              AuthorizationErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST.name(),
              "idempotency key was already used for a different request"),
          now);
    }
    if ("DEFERRED".equals(existing.status())) {
      if (isStaleAcademicGeneration(metadata, command)) {
        return finalizeTerminal(
            metadata,
            command,
            fingerprint,
            HandledCommand.rejected(ApplicationResult.rejected(
                AuthorizationErrorCode.STALE_EXECUTION_GENERATION.name(),
                "deferred execution generation is older than the latest observed generation")),
            now);
      }
      inbox.markDeferred(COMMAND_CONSUMER, metadata.messageId(), existing.pendingReason(), now);
      return CommandOutcome.deferred(existing.pendingReason());
    }
    if (!newDelivery) {
      return CommandOutcome.duplicate();
    }
    inbox.markApplied(COMMAND_CONSUMER, metadata.messageId(), now, now);
    ApplicationResult result = new ApplicationResult(
        ApplicationResult.Status.valueOf(existing.resultStatus()),
        existing.resultDataJson(),
        existing.errorCode() == null
            ? null
            : new ApplicationResult.Error(existing.errorCode(), existing.errorDetailsJson()));
    return CommandOutcome.terminal(result, List.of(), List.of());
  }

  private CommandOutcome finalizeStandaloneRejection(
      CommandMetadata metadata, AuthorizationCommand command, ApplicationResult rawResult, Instant now) {
    ApplicationResult result = CommandResultSupport.ensureImportExecutionData(command, rawResult);
    OutboundResultIntent resultIntent = resultIntent(metadata, command, result, null, null, now);
    OutboxIntentWriter.append(outbox, resultIntent);
    appendAudit(metadata, command, result, null, null, now);
    inbox.markApplied(COMMAND_CONSUMER, metadata.messageId(), now, now);
    return CommandOutcome.terminal(result, List.of(), List.of(resultIntent));
  }

  private CommandOutcome finalizeDeferred(
      CommandMetadata metadata,
      AuthorizationCommand command,
      String fingerprint,
      String pendingReason,
      Instant now) {
    IdempotencyRecord updated = new IdempotencyRecord(
        metadata.producer(),
        command.operationType(),
        metadata.idempotencyKey(),
        fingerprint,
        "DEFERRED",
        null,
        null,
        null,
        null,
        null,
        null,
        command.importExecution().importId(),
        command.importExecution().planItemId(),
        command.importExecution().executionGeneration(),
        pendingReason,
        now,
        null,
        now);
    idempotency.updateDeferred(updated);
    inbox.markDeferred(COMMAND_CONSUMER, metadata.messageId(), pendingReason, now);
    audit.append(new AuthorizationAuditRepository.AuditEntry(
        UUID.randomUUID(),
        metadata.messageId(),
        metadata.producer(),
        command.operationType(),
        command.targetAccountId(),
        null,
        command.operationType(),
        metadata.actor(),
        command.importExecution().authorizedByAccountId(),
        command.importExecution().importId(),
        metadata.correlationId(),
        metadata.causationId(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        "DEFERRED",
        pendingReason,
        null,
        now));
    return CommandOutcome.deferred(pendingReason);
  }

  private CommandOutcome finalizeTerminal(
      CommandMetadata metadata,
      AuthorizationCommand command,
      String fingerprint,
      HandledCommand handled,
      Instant now) {
    ApplicationResult result = CommandResultSupport.ensureImportExecutionData(command, handled.result());
    for (DomainEventIntent event : handled.events()) {
      OutboxIntentWriter.append(outbox, event);
    }
    OutboundResultIntent resultIntent = resultIntent(
        metadata, command, result, handled.aggregateId(), handled.aggregateVersion(), now);
    OutboxIntentWriter.append(outbox, resultIntent);
    ImportExecution execution = command.importExecution();
    IdempotencyRecord previous = idempotency.find(
            metadata.producer(), command.operationType(), metadata.idempotencyKey())
        .orElseThrow(() -> new IllegalStateException("terminal idempotency record is missing"));
    idempotency.update(new IdempotencyRecord(
        metadata.producer(),
        command.operationType(),
        metadata.idempotencyKey(),
        fingerprint,
        result.status() == ApplicationResult.Status.FAILED ? "FAILED" : "COMPLETED",
        result.status().name(),
        result.dataJson(),
        result.error() == null ? null : result.error().code(),
        result.error() == null ? null : CanonicalJson.write(Map.of("details", result.error().details())),
        handled.aggregateId(),
        handled.aggregateVersion(),
        execution == null ? null : execution.importId(),
        execution == null ? null : execution.planItemId(),
        execution == null ? null : execution.executionGeneration(),
        null,
        previous.createdAt(),
        now,
        now));
    inbox.markApplied(COMMAND_CONSUMER, metadata.messageId(), now, now);
    appendAudit(metadata, command, result, handled.aggregateId(), handled.aggregateVersion(), now);
    return CommandOutcome.terminal(result, handled.events(), List.of(resultIntent));
  }

  private OutboundResultIntent resultIntent(
      CommandMetadata metadata,
      AuthorizationCommand command,
      ApplicationResult result,
      UUID aggregateId,
      Integer aggregateVersion,
      Instant occurredAt) {
    return new OutboundResultIntent(
        UUID.randomUUID(),
        command.operationType() + "Result",
        1,
        "authorization",
        metadata.producer(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        metadata.actor(),
        aggregateId,
        aggregateVersion,
        metadata.correlationId(),
        metadata.messageId(),
        ResultIntentFactory.payload(result),
        occurredAt);
  }

  private void appendAudit(
      CommandMetadata metadata,
      AuthorizationCommand command,
      ApplicationResult result,
      UUID aggregateId,
      Integer aggregateVersion,
      Instant now) {
    ImportExecution execution = command.importExecution();
    audit.append(new AuthorizationAuditRepository.AuditEntry(
        UUID.randomUUID(),
        metadata.messageId(),
        metadata.producer(),
        command.operationType(),
        aggregateId,
        aggregateVersion,
        command.operationType(),
        metadata.actor(),
        execution == null ? null : execution.authorizedByAccountId(),
        execution == null ? null : execution.importId(),
        metadata.correlationId(),
        metadata.causationId(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        result.status().name(),
        result.error() == null ? null : result.error().code(),
        result.error() == null ? null : result.error().details(),
        now));
  }

  private HandledCommand execute(
      CommandMetadata metadata,
      AuthorizationCommand command,
      Optional<AccountRoleAssignment> targetRole,
      Optional<SystemAccess> targetAccess,
      Instant now) {
    return switch (command) {
      case AssignAccountRoleCommand assign -> assign(metadata, assign, targetRole, now);
      case ChangeAccountRoleCommand change -> change(metadata, change, targetRole, now);
      case GrantSystemAccessCommand grant -> grant(metadata, grant, targetRole, now);
      case SuspendSystemAccessCommand suspend -> suspend(metadata, suspend, targetAccess, now);
      case RevokeSystemAccessCommand revoke -> revoke(metadata, revoke, targetAccess, now);
      case RestoreSystemAccessCommand restore -> restore(metadata, restore, targetAccess, targetRole, now);
    };
  }

  private HandledCommand assign(
      CommandMetadata metadata,
      AssignAccountRoleCommand command,
      Optional<AccountRoleAssignment> targetRole,
      Instant now) {
    var account = accounts.findByAccountId(command.accountId());
    Optional<HandledCommand> academicWait = academicAccountWait(metadata, command, account);
    if (academicWait.isPresent()) {
      return academicWait.get();
    }
    if (account.isEmpty()) {
      throw reject(AuthorizationErrorCode.ACCOUNT_NOT_FOUND, "account projection is absent");
    }
    RoleCatalog role = activeRole(command.roleCode());
    if (!role.code().isCompatibleWith(account.get().loginType())) {
      throw reject(AuthorizationErrorCode.ROLE_NOT_ALLOWED_FOR_LOGIN_TYPE, "role is incompatible with login type");
    }
    if (targetRole.isPresent()) {
      if ("academic".equals(metadata.producer()) && targetRole.get().roleCode() == RoleCode.ALUMNO) {
        provisioning.evaluate(command.accountId(), trigger(metadata, command.importExecution(), now));
        return HandledCommand.success(
            ApplicationResult.succeeded(CommandResultSupport.withImportExecution(command, Map.of(
                "accountId", command.accountId(),
                "roleCode", targetRole.get().roleCode(),
                "changed", false,
                "newVersion", targetRole.get().version()))),
            List.of(),
            targetRole.get().accountId(),
            targetRole.get().version());
      }
      throw reject(AuthorizationErrorCode.ACCOUNT_ROLE_ALREADY_ASSIGNED, "account already has a role");
    }
    AccountRoleAssignment assignment = AccountRoleAssignment.createNew(
        command.accountId(), role.id(), role.code(), metadata.actor(), now);
    if (!accountRoles.insertIfAbsent(assignment)) {
      AccountRoleAssignment concurrent = accountRoles.findByAccountId(command.accountId()).orElseThrow(
          () -> new IllegalStateException("role insert lost its row"));
      if ("academic".equals(metadata.producer()) && concurrent.roleCode() == RoleCode.ALUMNO) {
        provisioning.evaluate(command.accountId(), trigger(metadata, command.importExecution(), now));
        return HandledCommand.success(
            ApplicationResult.succeeded(CommandResultSupport.withImportExecution(command, Map.of(
                "accountId", command.accountId(),
                "roleCode", concurrent.roleCode(),
                "changed", false,
                "newVersion", concurrent.version()))),
            List.of(),
            concurrent.accountId(),
            concurrent.version());
      }
      throw reject(AuthorizationErrorCode.ACCOUNT_ROLE_ALREADY_ASSIGNED, "account already has a role");
    }
    DomainEventIntent event = roleAssignedEvent(metadata, assignment, now);
    provisioning.evaluate(command.accountId(), trigger(metadata, command.importExecution(), now));
    return HandledCommand.success(
        ApplicationResult.succeeded(CommandResultSupport.withImportExecution(command, Map.of(
            "accountId", command.accountId(),
            "roleCode", role.code(),
            "changed", true,
            "newVersion", assignment.version()))),
        List.of(event), assignment.accountId(), assignment.version());
  }

  private HandledCommand change(
      CommandMetadata metadata,
      ChangeAccountRoleCommand command,
      Optional<AccountRoleAssignment> targetRole,
      Instant now) {
    AccountRoleAssignment current = targetRole.orElseThrow(
        () -> reject(AuthorizationErrorCode.ACCOUNT_ROLE_NOT_FOUND, "account has no role"));
    if (current.version() != command.expectedVersion()) {
      throw reject(AuthorizationErrorCode.ACCOUNT_ROLE_VERSION_CONFLICT, "role version does not match");
    }
    var account = accounts.findByAccountId(command.accountId())
        .orElseThrow(() -> reject(AuthorizationErrorCode.ACCOUNT_NOT_FOUND, "account projection is absent"));
    RoleCatalog newRole = activeRole(command.newRoleCode());
    if (!newRole.code().isCompatibleWith(account.loginType())) {
      throw reject(AuthorizationErrorCode.ROLE_NOT_ALLOWED_FOR_LOGIN_TYPE, "role is incompatible with login type");
    }
    if (current.roleCode() == newRole.code()) {
      throw reject(AuthorizationErrorCode.ROLE_ALREADY_ASSIGNED, "new role is already assigned");
    }

    List<SystemAccess> suspended = new ArrayList<>();
    List<DomainEventIntent> events = new ArrayList<>();
    AccountRoleAssignment changed = AccountRoleAssignment.reconstitute(
        current.accountId(),
        newRole.id(),
        newRole.code(),
        current.version() + 1,
        current.assignedBy(),
        metadata.actor(),
        current.assignedAt(),
        now);
    accountRoles.update(changed, command.expectedVersion());
    for (SystemAccess access : accesses.findByAccountId(command.accountId())) {
      if (access.status() != SystemAccessStatus.ACTIVE
          || roles.isAllowedInSystem(newRole.id(), access.systemId())) {
        continue;
      }
      SystemAccess suspendedAccess = access.suspend(
          SystemAccessReasonCode.ROLE_CHANGED, null, metadata.actor(), now);
      accesses.update(suspendedAccess, access.version());
      suspended.add(suspendedAccess);
      events.add(accessSuspendedEvent(metadata, suspendedAccess, access.status(), now));
    }
    events.add(roleChangedEvent(metadata, current, changed, now));
    provisioning.evaluate(command.accountId(), trigger(metadata, null, now));
    List<UUID> suspendedIds = suspended.stream().map(SystemAccess::systemAccessId).toList();
    return HandledCommand.success(
        ApplicationResult.succeeded(Map.of(
            "accountId", command.accountId(),
            "previousRole", current.roleCode(),
            "newRole", changed.roleCode(),
            "changed", true,
            "newVersion", changed.version(),
            "suspendedSystemAccessIds", suspendedIds,
            "suspendedSystemAccessCount", suspended.size())),
        events,
        changed.accountId(),
        changed.version());
  }

  private HandledCommand grant(
      CommandMetadata metadata,
      GrantSystemAccessCommand command,
      Optional<AccountRoleAssignment> targetRole,
      Instant now) {
    var account = accounts.findByAccountId(command.accountId());
    Optional<HandledCommand> academicWait = academicAccountWait(metadata, command, account);
    if (academicWait.isPresent()) {
      return academicWait.get();
    }
    if (account.isEmpty()) {
      throw reject(AuthorizationErrorCode.ACCOUNT_NOT_FOUND, "account projection is absent");
    }
    AccountRoleAssignment roleAssignment = targetRole.orElseThrow(
        () -> reject(AuthorizationErrorCode.ACCOUNT_ROLE_NOT_FOUND, "account has no role"));
    if ("academic".equals(metadata.producer()) && roleAssignment.roleCode() != RoleCode.ALUMNO) {
      throw reject(AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED, "Academic Grant requires ALUMNO target role");
    }
    RoleCatalog role = roles.findById(roleAssignment.roleId())
        .orElseThrow(() -> reject(AuthorizationErrorCode.ROLE_NOT_FOUND, "role is missing"));
    if (role.status() != CatalogStatus.ACTIVE) {
      throw reject(AuthorizationErrorCode.ROLE_INACTIVE, "role is inactive");
    }
    SystemCatalog system = activeSystem(command.systemCode());
    if (!roles.isAllowedInSystem(role.id(), system.id())) {
      throw reject(AuthorizationErrorCode.ROLE_NOT_ALLOWED_IN_SYSTEM, "role is not allowed in system");
    }
    validatePracticesGrantEligibility(metadata, command, system);

    Optional<SystemAccess> existing = accesses.findByAccountIdAndSystemId(command.accountId(), system.id());
    if (existing.isPresent()) {
      if ("academic".equals(metadata.producer())) {
        return HandledCommand.success(
            ApplicationResult.succeeded(CommandResultSupport.withImportExecution(command, Map.of(
                "systemAccessId", existing.get().systemAccessId(),
                "accountId", existing.get().accountId(),
                "systemCode", existing.get().systemCode(),
                "status", existing.get().status(),
                "changed", false,
                "newVersion", existing.get().version()))),
            List.of(),
            existing.get().systemAccessId(),
            existing.get().version());
      }
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_ALREADY_EXISTS, "account/system access already exists");
    }
    SystemAccess access = SystemAccess.createNew(
        command.systemAccessId(), command.accountId(), system.id(), system.code(), metadata.actor(), now);
    if (!accesses.insertIfAbsent(access)) {
      SystemAccess concurrent = accesses.findByAccountIdAndSystemId(command.accountId(), system.id())
          .orElseThrow(() -> new IllegalStateException("access insert lost its row"));
      if ("academic".equals(metadata.producer())) {
        return HandledCommand.success(
            ApplicationResult.succeeded(CommandResultSupport.withImportExecution(command, Map.of(
                "systemAccessId", concurrent.systemAccessId(),
                "accountId", concurrent.accountId(),
                "systemCode", concurrent.systemCode(),
                "status", concurrent.status(),
                "changed", false,
                "newVersion", concurrent.version()))),
            List.of(),
            concurrent.systemAccessId(),
            concurrent.version());
      }
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_ALREADY_EXISTS, "account/system access already exists");
    }
    DomainEventIntent event = accessGrantedEvent(metadata, access, roleAssignment, now);
    return HandledCommand.success(
        ApplicationResult.succeeded(CommandResultSupport.withImportExecution(command, Map.of(
            "systemAccessId", access.systemAccessId(),
            "accountId", access.accountId(),
            "systemCode", access.systemCode(),
            "status", access.status(),
            "changed", true,
            "newVersion", access.version()))),
        List.of(event), access.systemAccessId(), access.version());
  }

  private void validatePracticesGrantEligibility(
      CommandMetadata metadata, GrantSystemAccessCommand command, SystemCatalog system) {
    if (system.code() != SystemCode.PRACTICAS_PROFESIONALES || !"bff".equals(metadata.producer())) {
      return;
    }
    var projected = eligibility.findByAccountIdAndSystemCode(
        command.accountId(), SystemCode.PRACTICAS_PROFESIONALES.name())
        .orElseThrow(() -> reject(
            AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
            "Practices Grant requires academic eligibility"));
    String key = "eligibility:" + projected.studentAcademicProfileId() + ":" + projected.systemCode();
    if (!projected.eligible()
        || projected.policyVersion() != 1
        || !recoveryJobs.isProjectionTrusted("ACADEMIC_ELIGIBILITY")
        || !recoveryJobs.isEntityTrusted("ACADEMIC_ELIGIBILITY", key)) {
      throw reject(
          AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
          "Practices Grant target is not currently eligible/trustworthy");
    }
  }

  private HandledCommand suspend(
      CommandMetadata metadata,
      SuspendSystemAccessCommand command,
      Optional<SystemAccess> targetAccess,
      Instant now) {
    SystemAccess access = targetAccess.orElseThrow(
        () -> reject(AuthorizationErrorCode.SYSTEM_ACCESS_NOT_FOUND, "access does not exist"));
    if (access.version() != command.expectedVersion()) {
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_VERSION_CONFLICT, "access version does not match");
    }
    if (access.status() == SystemAccessStatus.SUSPENDED) {
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_ALREADY_SUSPENDED, "access is already suspended");
    }
    if (access.status() == SystemAccessStatus.REVOKED) {
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_REVOKED, "revoked access cannot be suspended");
    }
    validateDirectReason(command.reasonCode(), command.reasonDetail());
    SystemAccess changed = access.suspend(command.reasonCode(), command.reasonDetail(), metadata.actor(), now);
    accesses.update(changed, access.version());
    return HandledCommand.success(
        ApplicationResult.succeeded(Map.of(
            "systemAccessId", changed.systemAccessId(),
            "accountId", changed.accountId(),
            "status", changed.status(),
            "changed", true,
            "newVersion", changed.version())),
        List.of(accessSuspendedEvent(metadata, changed, access.status(), now)),
        changed.systemAccessId(),
        changed.version());
  }

  private HandledCommand revoke(
      CommandMetadata metadata,
      RevokeSystemAccessCommand command,
      Optional<SystemAccess> targetAccess,
      Instant now) {
    SystemAccess access = targetAccess.orElseThrow(
        () -> reject(AuthorizationErrorCode.SYSTEM_ACCESS_NOT_FOUND, "access does not exist"));
    if (access.version() != command.expectedVersion()) {
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_VERSION_CONFLICT, "access version does not match");
    }
    if (access.status() == SystemAccessStatus.REVOKED) {
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_ALREADY_REVOKED, "access is already revoked");
    }
    validateDirectReason(command.reasonCode(), command.reasonDetail());
    SystemAccess changed = access.revoke(command.reasonCode(), command.reasonDetail(), metadata.actor(), now);
    accesses.update(changed, access.version());
    return HandledCommand.success(
        ApplicationResult.succeeded(Map.of(
            "systemAccessId", changed.systemAccessId(),
            "accountId", changed.accountId(),
            "status", changed.status(),
            "changed", true,
            "newVersion", changed.version())),
        List.of(accessRevokedEvent(metadata, changed, access.status(), now)),
        changed.systemAccessId(),
        changed.version());
  }

  private HandledCommand restore(
      CommandMetadata metadata,
      RestoreSystemAccessCommand command,
      Optional<SystemAccess> targetAccess,
      Optional<AccountRoleAssignment> targetRole,
      Instant now) {
    SystemAccess access = targetAccess.orElseThrow(
        () -> reject(AuthorizationErrorCode.SYSTEM_ACCESS_NOT_FOUND, "access does not exist"));
    if (access.version() != command.expectedVersion()) {
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_VERSION_CONFLICT, "access version does not match");
    }
    if (access.status() == SystemAccessStatus.ACTIVE) {
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_ALREADY_ACTIVE, "access is already active");
    }
    AccountRoleAssignment role = targetRole.orElseThrow(
        () -> reject(AuthorizationErrorCode.ACCOUNT_ROLE_NOT_FOUND, "account has no role"));
    RoleCatalog roleCatalog = roles.findById(role.roleId())
        .orElseThrow(() -> reject(AuthorizationErrorCode.ROLE_NOT_FOUND, "role is missing"));
    if (!roles.isAllowedInSystem(roleCatalog.id(), access.systemId())) {
      throw reject(AuthorizationErrorCode.ROLE_NOT_ALLOWED_IN_SYSTEM, "role is no longer compatible with system");
    }
    if (access.status() == SystemAccessStatus.REVOKED && !isSuperAdmin(metadata.actor())) {
      throw reject(AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED, "only SUPERADMIN can restore REVOKED access");
    }
    SystemAccess changed = access.restore(metadata.actor(), now);
    accesses.update(changed, access.version());
    return HandledCommand.success(
        ApplicationResult.succeeded(Map.of(
            "systemAccessId", changed.systemAccessId(),
            "accountId", changed.accountId(),
            "status", changed.status(),
            "changed", true,
            "newVersion", changed.version())),
        List.of(accessRestoredEvent(metadata, changed, access.status(), now)),
        changed.systemAccessId(),
        changed.version());
  }

  private Optional<HandledCommand> academicAccountWait(
      CommandMetadata metadata,
      AuthorizationCommand command,
      Optional<AccountStatusProjectionRepository.AccountStatusProjection> account) {
    if (!"academic".equals(metadata.producer())) {
      return Optional.empty();
    }
    ImportExecution execution = command.importExecution();
    UUID targetAccountId = command.targetAccountId();
    boolean hasDependency = false;
    for (CausalDependency dependency : execution.causalDependencies()) {
      hasDependency = true;
      if (!targetAccountId.equals(dependency.expectedAggregateId())
          || !"CreateAccount".equals(dependency.priorCommandType())
          || !"AccountCreated".equals(dependency.expectedEventType())
          || !"SUCCEEDED".equals(dependency.priorResultStatus())
          || metadata.causationId() == null
          || !metadata.causationId().equals(dependency.priorResultMessageId())) {
        throw reject(
            AuthorizationErrorCode.ACTOR_CONTEXT_INVALID,
            "Academic causalDependency is not valid for Account provisioning");
      }
      if (account.isEmpty()
          || (dependency.minimumSourceVersion() != null
              && account.get().sourceVersion() < dependency.minimumSourceVersion())) {
        return Optional.of(HandledCommand.deferred("EVENT_PENDING"));
      }
    }

    boolean hasAccountEvidence = false;
    for (AuthoritativeEvidence evidence : execution.authoritativeEvidence()) {
      boolean refersToAccount = targetAccountId.equals(evidence.entityId())
          || "Account".equalsIgnoreCase(evidence.entityType());
      if (!refersToAccount) {
        continue;
      }
      hasAccountEvidence = true;
      if (!targetAccountId.equals(evidence.entityId())
          || !"Account".equalsIgnoreCase(evidence.entityType())
          || !"authentication".equals(evidence.sourceService())
          || !"CreateAccount".equals(evidence.sourceOperation())
          || !"SUCCEEDED".equals(evidence.sourceResultStatus())) {
        throw reject(
            AuthorizationErrorCode.ACTOR_CONTEXT_INVALID,
            "Academic authoritative Account evidence is invalid");
      }
      if (account.isEmpty()
          || (evidence.evidenceVersion() != null
              && account.get().sourceVersion() < evidence.evidenceVersion())) {
        return Optional.of(HandledCommand.deferred("PROJECTION_RECONCILIATION"));
      }
    }

    if (account.isEmpty() && !hasDependency && !hasAccountEvidence) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<SystemAccess> targetAccess(AuthorizationCommand command) {
    UUID accessId = switch (command) {
      case SuspendSystemAccessCommand suspend -> suspend.systemAccessId();
      case RevokeSystemAccessCommand revoke -> revoke.systemAccessId();
      case RestoreSystemAccessCommand restore -> restore.systemAccessId();
      default -> null;
    };
    return accessId == null ? Optional.empty() : accesses.findById(accessId);
  }

  private RoleCatalog activeRole(RoleCode code) {
    RoleCatalog role = roles.findByCode(code.name())
        .orElseThrow(() -> reject(AuthorizationErrorCode.ROLE_NOT_FOUND, "role does not exist"));
    if (role.status() != CatalogStatus.ACTIVE) {
      throw reject(AuthorizationErrorCode.ROLE_INACTIVE, "role is inactive");
    }
    return role;
  }

  private SystemCatalog activeSystem(SystemCode code) {
    SystemCatalog system = systems.findByCode(code.name())
        .orElseThrow(() -> reject(AuthorizationErrorCode.SYSTEM_NOT_FOUND, "system does not exist"));
    if (system.status() != CatalogStatus.ACTIVE) {
      throw reject(AuthorizationErrorCode.SYSTEM_INACTIVE, "system is inactive");
    }
    return system;
  }

  private void validateDirectReason(SystemAccessReasonCode reason, String detail) {
    if (reason == SystemAccessReasonCode.ROLE_CHANGED) {
      throw reject(AuthorizationErrorCode.REASON_CODE_INVALID, "ROLE_CHANGED is reserved for ChangeAccountRole");
    }
    if (reason == SystemAccessReasonCode.OTHER && (detail == null || detail.isBlank())) {
      throw reject(AuthorizationErrorCode.REASON_DETAIL_REQUIRED, "OTHER requires reasonDetail");
    }
  }

  private boolean isSuperAdmin(ActorRef actor) {
    return actor.accountId() != null
        && accountRoles.findByAccountId(actor.accountId())
            .map(role -> role.roleCode() == RoleCode.SUPERADMIN)
            .orElse(false);
  }

  private PracticesProvisioningService.Trigger trigger(
      CommandMetadata metadata, ImportExecution execution, Instant now) {
    return new PracticesProvisioningService.Trigger(
        metadata.messageId(),
        metadata.producer(),
        metadata.correlationId(),
        metadata.causationId(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        execution == null ? null : execution.authorizedByAccountId(),
        execution == null ? null : execution.importId(),
        now);
  }

  private DomainEventIntent roleAssignedEvent(
      CommandMetadata metadata, AccountRoleAssignment assignment, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", assignment.accountId());
    payload.put("roleId", assignment.roleId());
    payload.put("roleCode", assignment.roleCode());
    payload.put("assignedAt", assignment.assignedAt());
    return event(
        metadata,
        "AccountRoleAssigned",
        "authorization.account-role.assigned",
        assignment.accountId(),
        assignment.version(),
        payload,
        now);
  }

  private DomainEventIntent roleChangedEvent(
      CommandMetadata metadata,
      AccountRoleAssignment previous,
      AccountRoleAssignment changed,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", changed.accountId());
    payload.put("previousRoleId", previous.roleId());
    payload.put("previousRoleCode", previous.roleCode());
    payload.put("newRoleId", changed.roleId());
    payload.put("newRoleCode", changed.roleCode());
    payload.put("changedAt", changed.updatedAt());
    return event(
        metadata,
        "AccountRoleChanged",
        "authorization.account-role.changed",
        changed.accountId(),
        changed.version(),
        payload,
        now);
  }

  private DomainEventIntent accessGrantedEvent(
      CommandMetadata metadata,
      SystemAccess access,
      AccountRoleAssignment role,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("systemAccessId", access.systemAccessId());
    payload.put("accountId", access.accountId());
    payload.put("systemId", access.systemId());
    payload.put("systemCode", access.systemCode());
    payload.put("roleId", role.roleId());
    payload.put("roleCode", role.roleCode());
    payload.put("status", access.status());
    payload.put("grantedAt", access.grantedAt());
    return event(
        metadata,
        "SystemAccessGranted",
        "authorization.system-access.granted",
        access.systemAccessId(),
        access.version(),
        payload,
        now);
  }

  private DomainEventIntent accessSuspendedEvent(
      CommandMetadata metadata,
      SystemAccess access,
      SystemAccessStatus previousStatus,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("systemAccessId", access.systemAccessId());
    payload.put("accountId", access.accountId());
    payload.put("systemId", access.systemId());
    payload.put("systemCode", access.systemCode());
    payload.put("previousStatus", previousStatus);
    payload.put("status", access.status());
    payload.put("reasonCode", access.reasonCode());
    if (access.reasonDetail() != null) {
      payload.put("reasonDetail", access.reasonDetail());
    }
    payload.put("suspendedAt", access.suspendedAt());
    return event(
        metadata,
        "SystemAccessSuspended",
        "authorization.system-access.suspended",
        access.systemAccessId(),
        access.version(),
        payload,
        now);
  }

  private DomainEventIntent accessRevokedEvent(
      CommandMetadata metadata,
      SystemAccess access,
      SystemAccessStatus previousStatus,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("systemAccessId", access.systemAccessId());
    payload.put("accountId", access.accountId());
    payload.put("systemId", access.systemId());
    payload.put("systemCode", access.systemCode());
    payload.put("previousStatus", previousStatus);
    payload.put("status", access.status());
    payload.put("reasonCode", access.reasonCode());
    if (access.reasonDetail() != null) {
      payload.put("reasonDetail", access.reasonDetail());
    }
    payload.put("revokedAt", access.revokedAt());
    return event(
        metadata,
        "SystemAccessRevoked",
        "authorization.system-access.revoked",
        access.systemAccessId(),
        access.version(),
        payload,
        now);
  }

  private DomainEventIntent accessRestoredEvent(
      CommandMetadata metadata,
      SystemAccess access,
      SystemAccessStatus previousStatus,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("systemAccessId", access.systemAccessId());
    payload.put("accountId", access.accountId());
    payload.put("systemId", access.systemId());
    payload.put("systemCode", access.systemCode());
    payload.put("previousStatus", previousStatus);
    payload.put("status", access.status());
    payload.put("restoredAt", access.updatedAt());
    return event(
        metadata,
        "SystemAccessRestored",
        "authorization.system-access.restored",
        access.systemAccessId(),
        access.version(),
        payload,
        now);
  }

  private DomainEventIntent event(
      CommandMetadata metadata,
      String type,
      String routingKey,
      UUID aggregateId,
      int aggregateVersion,
      Map<String, Object> payload,
      Instant now) {
    return new DomainEventIntent(
        UUID.randomUUID(),
        type,
        1,
        "authorization",
        routingKey,
        metadata.operationId(),
        metadata.idempotencyKey(),
        metadata.actor(),
        aggregateId,
        aggregateVersion,
        metadata.correlationId(),
        metadata.messageId(),
        CanonicalJson.write(payload),
        now);
  }

  private static AuthorizationRuleViolationException reject(
      AuthorizationErrorCode code, String message) {
    return new AuthorizationRuleViolationException(code, message);
  }

  private record HandledCommand(
      ApplicationResult result,
      List<DomainEventIntent> events,
      UUID aggregateId,
      Integer aggregateVersion,
      boolean deferred,
      String pendingReason) {

    private HandledCommand {
      events = List.copyOf(events == null ? List.of() : events);
      if (deferred != (pendingReason != null) || (deferred && result != null)) {
        throw new IllegalArgumentException("invalid handled command state");
      }
    }

    static HandledCommand success(ApplicationResult result) {
      return new HandledCommand(result, List.of(), null, null, false, null);
    }

    static HandledCommand success(
        ApplicationResult result,
        List<DomainEventIntent> events,
        UUID aggregateId,
        int aggregateVersion) {
      return new HandledCommand(result, events, aggregateId, aggregateVersion, false, null);
    }

    static HandledCommand deferred(String pendingReason) {
      return new HandledCommand(null, List.of(), null, null, true, pendingReason);
    }

    static HandledCommand rejected(ApplicationResult result) {
      return success(result);
    }
  }
}
