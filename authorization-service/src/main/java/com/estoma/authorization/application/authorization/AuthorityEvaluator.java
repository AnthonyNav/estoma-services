package com.estoma.authorization.application.authorization;

import com.estoma.authorization.application.command.AssignAccountRoleCommand;
import com.estoma.authorization.application.command.AuthorizationCommand;
import com.estoma.authorization.application.command.ChangeAccountRoleCommand;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.GrantSystemAccessCommand;
import com.estoma.authorization.application.command.ImportExecution;
import com.estoma.authorization.application.command.RestoreSystemAccessCommand;
import com.estoma.authorization.application.exception.AuthorizationErrorCode;
import com.estoma.authorization.application.exception.AuthorizationRuleViolationException;
import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.RoleCatalogRepository;
import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.ActorType;
import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.ProjectedStatus;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCatalog;
import com.estoma.authorization.domain.model.SystemCode;
import java.util.Optional;
import java.util.UUID;

public final class AuthorityEvaluator {

  private static final String EVENT_CONSUMER = "authorization-events";
  private final AccountStatusProjectionRepository accounts;
  private final PersonStatusProjectionRepository persons;
  private final AccountRoleRepository accountRoles;
  private final RoleCatalogRepository roles;
  private final SystemCatalogRepository systems;
  private final ProcessedMessageRepository inbox;
  private final ProjectionRecoveryJobRepository recoveryJobs;

  public AuthorityEvaluator(
      AccountStatusProjectionRepository accounts,
      PersonStatusProjectionRepository persons,
      AccountRoleRepository accountRoles,
      RoleCatalogRepository roles,
      SystemCatalogRepository systems,
      ProcessedMessageRepository inbox,
      ProjectionRecoveryJobRepository recoveryJobs) {
    this.accounts = accounts;
    this.persons = persons;
    this.accountRoles = accountRoles;
    this.roles = roles;
    this.systems = systems;
    this.inbox = inbox;
    this.recoveryJobs = recoveryJobs;
  }

  public void authorize(
      CommandMetadata metadata,
      AuthorizationCommand command,
      Optional<AccountRoleAssignment> targetRole,
      Optional<SystemAccess> targetAccess) {
    if ("bff".equals(metadata.producer())) {
      authorizeBff(metadata, command, targetRole, targetAccess);
      return;
    }
    if ("academic".equals(metadata.producer())) {
      authorizeAcademic(metadata, command);
      return;
    }
    throw reject(AuthorizationErrorCode.PRODUCER_NOT_ALLOWED, "producer is not allowlisted");
  }

  private void authorizeBff(
      CommandMetadata metadata,
      AuthorizationCommand command,
      Optional<AccountRoleAssignment> targetRole,
      Optional<SystemAccess> targetAccess) {
    ActorRef actor = metadata.actor();
    if (actor.type() != ActorType.ACCOUNT || actor.accountId() == null) {
      throw reject(AuthorizationErrorCode.ACTOR_CONTEXT_INVALID, "BFF commands require an ACCOUNT actor");
    }
    if (!recoveryJobs.isProjectionTrusted("ACCOUNT_STATUS")
        || !recoveryJobs.isProjectionTrusted("PERSON_STATUS")
        || !recoveryJobs.isEntityTrusted("ACCOUNT_STATUS", "account:" + actor.accountId())) {
      throw reject(AuthorizationErrorCode.ACTOR_NOT_AUTHORIZED, "actor projection recovery is not trusted");
    }
    var actorAccount = accounts.findByAccountId(actor.accountId());
    if (actorAccount.isPresent()
        && !recoveryJobs.isEntityTrusted(
            "PERSON_STATUS", "person:" + actorAccount.get().personId())) {
      throw reject(AuthorizationErrorCode.ACTOR_NOT_AUTHORIZED, "actor person recovery is not trusted");
    }
    var actorPerson = actorAccount.flatMap(account -> persons.findByPersonId(account.personId()));
    if (actorAccount.isEmpty()
        || actorPerson.isEmpty()
        || actorAccount.get().status() != ProjectedStatus.ACTIVE
        || actorPerson.get().status() != ProjectedStatus.ACTIVE
        || inbox.hasDeferredNewerThan(EVENT_CONSUMER, actor.accountId(), actorAccount.get().sourceVersion())
        || inbox.hasDeferredNewerThan(
            EVENT_CONSUMER, actorAccount.get().personId(), actorPerson.get().sourceVersion())) {
      throw reject(
          AuthorizationErrorCode.ACTOR_NOT_AUTHORIZED,
          "actor projections are not active and trustworthy");
    }
    AccountRoleAssignment actorRole = accountRoles.findByAccountId(actor.accountId())
        .orElseThrow(() -> reject(AuthorizationErrorCode.ACTOR_NOT_AUTHORIZED, "actor has no role"));
    var actorCatalog = roles.findById(actorRole.roleId())
        .orElseThrow(() -> reject(AuthorizationErrorCode.ACTOR_NOT_AUTHORIZED, "actor role is missing"));
    if (actorCatalog.status() != CatalogStatus.ACTIVE) {
      throw reject(AuthorizationErrorCode.ACTOR_NOT_AUTHORIZED, "actor role is inactive");
    }

    UUID targetAccountId = targetAccess.map(SystemAccess::accountId).orElse(command.targetAccountId());
    if (targetAccountId == null) {
      throw reject(AuthorizationErrorCode.SYSTEM_ACCESS_NOT_FOUND, "access target is not available");
    }
    if (targetAccountId.equals(actor.accountId())) {
      throw reject(AuthorizationErrorCode.SELF_ADMINISTRATION_FORBIDDEN, "self-administration is forbidden");
    }
    if (targetRole.map(role -> role.roleCode() == RoleCode.SUPERADMIN).orElse(false)
        || requestedTargetRole(command) == RoleCode.SUPERADMIN) {
      throw reject(AuthorizationErrorCode.SUPERADMIN_TARGET_FORBIDDEN, "SUPERADMIN target is outside V1");
    }
    if (command instanceof RestoreSystemAccessCommand) {
      requireTrustworthyActiveTarget(targetAccountId);
    }

    RoleCode actorRoleCode = actorRole.roleCode();
    if (actorRoleCode == RoleCode.SUPERADMIN) {
      return;
    }
    if (actorRoleCode != RoleCode.ADMINISTRADOR_LAVADO) {
      throw reject(
          AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
          "actor role has no V1 administration scope");
    }
    if (command instanceof ChangeAccountRoleCommand) {
      throw reject(
          AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
          "ADMINISTRADOR_LAVADO cannot change roles");
    }
    if (command instanceof AssignAccountRoleCommand assign
        && assign.roleCode() != RoleCode.SUPERVISOR_LAVADO) {
      throw reject(AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED, "role is outside Lavado scope");
    }
    if (!(command instanceof AssignAccountRoleCommand)
        && targetRole.map(role -> role.roleCode() != RoleCode.SUPERVISOR_LAVADO).orElse(true)) {
      throw reject(AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED, "target role is outside Lavado scope");
    }
    Optional<SystemCatalog> targetSystem = targetSystem(command, targetAccess);
    if (targetSystem.isEmpty() || targetSystem.get().code() != SystemCode.LAVADO_ULTRASONICO) {
      throw reject(AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED, "system is outside Lavado scope");
    }
    if (command instanceof RestoreSystemAccessCommand
        && targetAccess.map(access -> access.status() != SystemAccessStatus.SUSPENDED).orElse(true)) {
      throw reject(
          AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
          "Lavado restore only supports SUSPENDED");
    }
  }

  private void requireTrustworthyActiveTarget(UUID accountId) {
    if (!recoveryJobs.isProjectionTrusted("ACCOUNT_STATUS")
        || !recoveryJobs.isProjectionTrusted("PERSON_STATUS")
        || !recoveryJobs.isEntityTrusted("ACCOUNT_STATUS", "account:" + accountId)) {
      throw reject(
          AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
          "target recovery is not trusted for restore");
    }
    var account = accounts.findByAccountId(accountId)
        .orElseThrow(() -> reject(AuthorizationErrorCode.ACCOUNT_NOT_FOUND, "target account projection is absent"));
    if (!recoveryJobs.isEntityTrusted("PERSON_STATUS", "person:" + account.personId())) {
      throw reject(
          AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
          "target person recovery is not trusted for restore");
    }
    var person = persons.findByPersonId(account.personId())
        .orElseThrow(() -> reject(AuthorizationErrorCode.ACCOUNT_NOT_FOUND, "target person projection is absent"));
    if (account.status() != ProjectedStatus.ACTIVE
        || person.status() != ProjectedStatus.ACTIVE
        || inbox.hasDeferredNewerThan(EVENT_CONSUMER, accountId, account.sourceVersion())
        || inbox.hasDeferredNewerThan(EVENT_CONSUMER, account.personId(), person.sourceVersion())) {
      throw reject(
          AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
          "target account/person is inactive or not trustworthy for restore");
    }
  }

  private void authorizeAcademic(CommandMetadata metadata, AuthorizationCommand command) {
    if (metadata.actor().type() != ActorType.SYSTEM || !"academic".equals(metadata.actor().service())) {
      throw reject(
          AuthorizationErrorCode.ACTOR_CONTEXT_INVALID,
          "Academic requires SYSTEM/academic actor");
    }
    ImportExecution execution = command.importExecution();
    if (execution == null) {
      throw reject(AuthorizationErrorCode.ACTOR_CONTEXT_INVALID, "Academic requires importExecution");
    }
    if (command instanceof AssignAccountRoleCommand assign) {
      if (assign.roleCode() != RoleCode.ALUMNO || !"ASSIGN_STUDENT_ROLE".equals(execution.scope())) {
        throw reject(AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED, "Academic role scope is invalid");
      }
      return;
    }
    if (command instanceof GrantSystemAccessCommand grant) {
      if (grant.systemCode() != SystemCode.LAVADO_ULTRASONICO
          || !"GRANT_STUDENT_SYSTEM_ACCESS".equals(execution.scope())) {
        throw reject(AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED, "Academic access scope is invalid");
      }
      return;
    }
    throw reject(
        AuthorizationErrorCode.TARGET_SCOPE_NOT_ALLOWED,
        "Academic operation is not allowlisted");
  }

  private RoleCode requestedTargetRole(AuthorizationCommand command) {
    if (command instanceof AssignAccountRoleCommand assign) {
      return assign.roleCode();
    }
    if (command instanceof ChangeAccountRoleCommand change) {
      return change.newRoleCode();
    }
    return null;
  }

  private Optional<SystemCatalog> targetSystem(
      AuthorizationCommand command, Optional<SystemAccess> targetAccess) {
    if (command instanceof AssignAccountRoleCommand) {
      return systems.findByCode(SystemCode.LAVADO_ULTRASONICO.name());
    }
    if (command instanceof GrantSystemAccessCommand grant) {
      return systems.findByCode(grant.systemCode().name());
    }
    return targetAccess.flatMap(access -> systems.findById(access.systemId()));
  }

  private static AuthorizationRuleViolationException reject(
      AuthorizationErrorCode code, String message) {
    return new AuthorizationRuleViolationException(code, message);
  }
}
