package com.estoma.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.application.command.AssignAccountRoleCommand;
import com.estoma.authorization.application.command.CausalDependency;
import com.estoma.authorization.application.command.ChangeAccountRoleCommand;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.GrantSystemAccessCommand;
import com.estoma.authorization.application.command.ImportExecution;
import com.estoma.authorization.application.command.ApplicationResult;
import com.estoma.authorization.application.command.AuthorizationCommandDispatcher;
import com.estoma.authorization.application.command.RevokeSystemAccessCommand;
import com.estoma.authorization.application.command.RestoreSystemAccessCommand;
import com.estoma.authorization.application.command.SuspendSystemAccessCommand;
import com.estoma.authorization.application.projection.AcademicEligibilityProjectionEvent;
import com.estoma.authorization.application.projection.ProjectionEventMetadata;
import com.estoma.authorization.application.projection.ProjectionEventProcessor;
import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.SystemAccessRepository;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemAccessReasonCode;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCode;
import com.estoma.authorization.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuthorizationApplicationIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID SUPERADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000205");
  private static final UUID SUPERVISOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
  private static final UUID ADMIN_PRACTICAS_ID = UUID.fromString("00000000-0000-0000-0000-000000000204");
  private static final UUID LAVADO_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID PRACTICAS_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
  private static final UUID ALUMNO_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

  @Autowired private AuthorizationCommandDispatcher dispatcher;
  @Autowired private ProjectionEventProcessor projectionEvents;
  @Autowired private AccountRoleRepository accountRoles;
  @Autowired private AccountStatusProjectionRepository accounts;
  @Autowired private PersonStatusProjectionRepository persons;
  @Autowired private SystemAccessRepository accesses;
  @Autowired private JdbcClient jdbc;

  @Test
  void bffAssignIsAtomicAndSameIntentIsReplayedWithoutAnotherOutboxMessage() {
    UUID actorId = seedSuperadmin();
    UUID targetId = seedAccount(LoginType.USERNAME);
    UUID correlationId = UUID.randomUUID();
    CommandMetadata metadata = metadata(
        "AssignAccountRole", "bff", ActorRef.account(actorId), correlationId, "assign-key");

    var first = dispatcher.dispatch(
        metadata,
        new AssignAccountRoleCommand(targetId, RoleCode.ADMINISTRADOR_PRACTICAS, null));

    assertThat(first.result()).get().extracting(ApplicationResult::status).isEqualTo(ApplicationResult.Status.SUCCEEDED);
    assertThat(accountRoles.findByAccountId(targetId)).get().extracting(AccountRoleAssignment::roleCode)
        .isEqualTo(RoleCode.ADMINISTRADOR_PRACTICAS);
    long outboxCount = outboxCount(correlationId);
    assertThat(outboxCount).isEqualTo(2);

    var replay = dispatcher.dispatch(
        metadata,
        new AssignAccountRoleCommand(targetId, RoleCode.ADMINISTRADOR_PRACTICAS, null));

    assertThat(replay.result()).get().extracting(ApplicationResult::status).isEqualTo(ApplicationResult.Status.SUCCEEDED);
    assertThat(outboxCount(correlationId)).isEqualTo(outboxCount);
  }

  @Test
  void changeRoleSuspendsEveryIncompatibleActiveAccessInTheSameTransaction() {
    UUID actorId = seedSuperadmin();
    UUID targetId = seedAccount(LoginType.USERNAME);
    AccountRoleAssignment role = AccountRoleAssignment.createNew(
        targetId, SUPERVISOR_ID, RoleCode.SUPERVISOR_LAVADO, ActorRef.system("academic"), NOW);
    accountRoles.insert(role);
    UUID accessId = UUID.randomUUID();
    accesses.insert(SystemAccess.createNew(
        accessId, targetId, LAVADO_ID, SystemCode.LAVADO_ULTRASONICO, ActorRef.account(actorId), NOW));

    var outcome = dispatcher.dispatch(
        metadata("ChangeAccountRole", "bff", ActorRef.account(actorId), UUID.randomUUID(), "change-key"),
        new ChangeAccountRoleCommand(targetId, RoleCode.ADMINISTRADOR_PRACTICAS, 1));

    assertThat(outcome.result()).get().extracting(ApplicationResult::status).isEqualTo(ApplicationResult.Status.SUCCEEDED);
    assertThat(accountRoles.findByAccountId(targetId)).get().extracting(AccountRoleAssignment::roleCode)
        .isEqualTo(RoleCode.ADMINISTRADOR_PRACTICAS);
    assertThat(accesses.findById(accessId)).get().extracting(SystemAccess::status)
        .isEqualTo(SystemAccessStatus.SUSPENDED);
    assertThat(outboxCountForAggregate(targetId)).isEqualTo(2);
  }

  @Test
  void academicAssignWithCausalEvidenceRemainsDeferredWithoutTerminalResult() {
    UUID targetId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    ImportExecution execution = new ImportExecution(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        "ASSIGN_STUDENT_ROLE",
        List.of(new CausalDependency(
            "CreateAccount",
            UUID.randomUUID(),
            "SUCCEEDED",
            "AccountCreated",
            targetId,
            1)),
        List.of());

    var outcome = dispatcher.dispatch(
        metadata("AssignAccountRole", "academic", ActorRef.system("academic"), correlationId, "academic-key"),
        new AssignAccountRoleCommand(targetId, RoleCode.ALUMNO, execution));

    assertThat(outcome.deferred()).isTrue();
    assertThat(outcome.pendingReason()).isEqualTo("EVENT_PENDING");
    assertThat(outcome.result()).isEmpty();
    assertThat(jdbc.sql(
            "SELECT status, pending_reason FROM command_idempotency_records WHERE idempotency_key = :key")
        .param("key", "academic-key")
        .query((resultSet, rowNum) -> List.of(resultSet.getString(1), resultSet.getString(2)))
        .single()).containsExactly("DEFERRED", "EVENT_PENDING");
    assertThat(outboxCount(correlationId)).isZero();
  }

  @Test
  void olderAcademicGenerationIsRejectedAfterAnewerGenerationWasObserved() {
    UUID targetId = UUID.randomUUID();
    UUID importId = UUID.randomUUID();
    UUID planItemId = UUID.randomUUID();
    CausalDependency dependency = new CausalDependency(
        "CreateAccount", UUID.randomUUID(), "SUCCEEDED", "AccountCreated", targetId, 1);
    ImportExecution newer = new ImportExecution(
        importId, planItemId, 2, UUID.randomUUID(), "ASSIGN_STUDENT_ROLE", List.of(dependency), List.of());
    dispatcher.dispatch(
        metadata("AssignAccountRole", "academic", ActorRef.system("academic"), UUID.randomUUID(), "generation-2"),
        new AssignAccountRoleCommand(targetId, RoleCode.ALUMNO, newer));

    ImportExecution older = new ImportExecution(
        importId, planItemId, 1, UUID.randomUUID(), "ASSIGN_STUDENT_ROLE", List.of(dependency), List.of());
    var outcome = dispatcher.dispatch(
        metadata("AssignAccountRole", "academic", ActorRef.system("academic"), UUID.randomUUID(), "generation-1"),
        new AssignAccountRoleCommand(targetId, RoleCode.ALUMNO, older));

    assertThat(outcome.result()).get().satisfies(result -> {
      assertThat(result.status()).isEqualTo(ApplicationResult.Status.REJECTED);
      assertThat(result.error()).extracting(ApplicationResult.Error::code)
          .isEqualTo("STALE_EXECUTION_GENERATION");
    });
  }

  @Test
  void eligibilityEventConvergesPracticesAccessExactlyOnce() {
    UUID accountId = seedAccount(LoginType.MATRICULA);
    accountRoles.insert(AccountRoleAssignment.createNew(
        accountId, ALUMNO_ID, RoleCode.ALUMNO, ActorRef.system("academic"), NOW));
    UUID eligibilityId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    ProjectionEventMetadata metadata = new ProjectionEventMetadata(
        messageId,
        "StudentAcademicEligibilityChanged",
        1,
        "academic",
        NOW,
        UUID.randomUUID(),
        null,
        null,
        null,
        ActorRef.system("academic"),
        eligibilityId,
        1);
    AcademicEligibilityProjectionEvent event = new AcademicEligibilityProjectionEvent(
        metadata,
        eligibilityId,
        profileId,
        "PRACTICAS_PROFESIONALES",
        accountId,
        true,
        1,
        1,
        1,
        NOW,
        "{}",
        true);

    assertThat(projectionEvents.process(event).status()).isEqualTo(ProjectionEventProcessor.Status.APPLIED);
    assertThat(accesses.findByAccountIdAndSystemId(accountId, PRACTICAS_ID)).get()
        .extracting(SystemAccess::status).isEqualTo(SystemAccessStatus.ACTIVE);
    assertThat(projectionEvents.process(event).changed()).isFalse();
    assertThat(accesses.findByAccountIdAndSystemId(accountId, PRACTICAS_ID)).isPresent();
  }

  @Test
  void academicGrantIsEnsurePreserveAndDoesNotRestoreSuspendedAccess() {
    UUID accountId = seedAccount(LoginType.MATRICULA);
    accountRoles.insert(AccountRoleAssignment.createNew(
        accountId, ALUMNO_ID, RoleCode.ALUMNO, ActorRef.system("academic"), NOW));
    UUID accessId = UUID.randomUUID();
    SystemAccess granted = SystemAccess.createNew(
        accessId, accountId, LAVADO_ID, SystemCode.LAVADO_ULTRASONICO, ActorRef.system("academic"), NOW);
    accesses.insert(granted.suspend(
        SystemAccessReasonCode.ADMINISTRATIVE, null, ActorRef.account(UUID.randomUUID()), NOW.plusSeconds(1)));
    ImportExecution execution = ImportExecution.of(
        UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), "GRANT_STUDENT_SYSTEM_ACCESS");

    var outcome = dispatcher.dispatch(
        metadata("GrantSystemAccess", "academic", ActorRef.system("academic"), UUID.randomUUID(), "grant-key"),
        new GrantSystemAccessCommand(accessId, accountId, SystemCode.LAVADO_ULTRASONICO, execution));

    assertThat(outcome.result()).get().satisfies(result -> {
      assertThat(result.status()).isEqualTo(ApplicationResult.Status.SUCCEEDED);
      assertThat(result.dataJson()).contains("\"changed\":false");
    });
    assertThat(accesses.findById(accessId)).get().extracting(SystemAccess::status)
        .isEqualTo(SystemAccessStatus.SUSPENDED);
  }

  @Test
  void revokeAndRestoreRespectLifecycleAndVersion() {
    UUID actorId = seedSuperadmin();
    UUID targetId = seedAccount(LoginType.USERNAME);
    accountRoles.insert(AccountRoleAssignment.createNew(
        targetId, SUPERVISOR_ID, RoleCode.SUPERVISOR_LAVADO, ActorRef.account(actorId), NOW));
    UUID accessId = UUID.randomUUID();
    accesses.insert(SystemAccess.createNew(
        accessId, targetId, LAVADO_ID, SystemCode.LAVADO_ULTRASONICO, ActorRef.account(actorId), NOW));

    var revoked = dispatcher.dispatch(
        metadata("RevokeSystemAccess", "bff", ActorRef.account(actorId), UUID.randomUUID(), "revoke-key"),
        new RevokeSystemAccessCommand(accessId, 1, SystemAccessReasonCode.SECURITY, null));
    assertThat(revoked.result()).get().extracting(ApplicationResult::status).isEqualTo(ApplicationResult.Status.SUCCEEDED);
    assertThat(accesses.findById(accessId)).get().extracting(SystemAccess::status).isEqualTo(SystemAccessStatus.REVOKED);

    var restored = dispatcher.dispatch(
        metadata("RestoreSystemAccess", "bff", ActorRef.account(actorId), UUID.randomUUID(), "restore-key"),
        new RestoreSystemAccessCommand(accessId, 2));
    assertThat(restored.result()).get().extracting(ApplicationResult::status).isEqualTo(ApplicationResult.Status.SUCCEEDED);
    assertThat(accesses.findById(accessId)).get().extracting(SystemAccess::status).isEqualTo(SystemAccessStatus.ACTIVE);

    var suspended = dispatcher.dispatch(
        metadata("SuspendSystemAccess", "bff", ActorRef.account(actorId), UUID.randomUUID(), "suspend-key"),
        new SuspendSystemAccessCommand(accessId, 3, SystemAccessReasonCode.ADMINISTRATIVE, null));
    assertThat(suspended.result()).get().extracting(ApplicationResult::status).isEqualTo(ApplicationResult.Status.SUCCEEDED);
    assertThat(accesses.findById(accessId)).get().extracting(SystemAccess::status).isEqualTo(SystemAccessStatus.SUSPENDED);
  }

  @Test
  void authorityFailsClosedWhenHumanActorPersonProjectionIsMissing() {
    UUID actorId = UUID.randomUUID();
    accounts.upsertIfNewer(new AccountStatusProjectionRepository.AccountStatusProjection(
        actorId, UUID.randomUUID(), LoginType.USERNAME, ProjectedStatus.ACTIVE, 1, NOW));
    accountRoles.insert(AccountRoleAssignment.createNew(
        actorId, SUPERADMIN_ID, RoleCode.SUPERADMIN, ActorRef.account(actorId), NOW));
    UUID targetId = seedAccount(LoginType.USERNAME);

    var outcome = dispatcher.dispatch(
        metadata("AssignAccountRole", "bff", ActorRef.account(actorId), UUID.randomUUID(), "closed-key"),
        new AssignAccountRoleCommand(targetId, RoleCode.ADMINISTRADOR_PRACTICAS, null));

    assertThat(outcome.result()).get().satisfies(result -> {
      assertThat(result.status()).isEqualTo(ApplicationResult.Status.REJECTED);
      assertThat(result.error()).extracting(ApplicationResult.Error::code)
          .isEqualTo("ACTOR_NOT_AUTHORIZED");
    });
    assertThat(accountRoles.findByAccountId(targetId)).isEmpty();
  }

  private UUID seedSuperadmin() {
    UUID actorId = seedAccount(LoginType.USERNAME);
    accountRoles.insert(AccountRoleAssignment.createNew(
        actorId, SUPERADMIN_ID, RoleCode.SUPERADMIN, ActorRef.account(actorId), NOW));
    return actorId;
  }

  private UUID seedAccount(LoginType loginType) {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    persons.upsertIfNewer(new PersonStatusProjectionRepository.PersonStatusProjection(
        personId, ProjectedStatus.ACTIVE, 1, NOW));
    accounts.upsertIfNewer(new AccountStatusProjectionRepository.AccountStatusProjection(
        accountId, personId, loginType, ProjectedStatus.ACTIVE, 1, NOW));
    return accountId;
  }

  private static CommandMetadata metadata(
      String type, String producer, ActorRef actor, UUID correlationId, String idempotencyKey) {
    return CommandMetadata.create(
        UUID.randomUUID(), type, producer, correlationId, UUID.randomUUID(), idempotencyKey, actor, NOW);
  }

  private long outboxCount(UUID correlationId) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE correlation_id = :correlationId")
        .param("correlationId", correlationId)
        .query(Long.class)
        .single();
  }

  private long outboxCountForAggregate(UUID aggregateId) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id = :aggregateId")
        .param("aggregateId", aggregateId)
        .query(Long.class)
        .single();
  }
}
