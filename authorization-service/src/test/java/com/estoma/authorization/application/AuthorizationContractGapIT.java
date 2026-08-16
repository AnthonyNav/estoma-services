package com.estoma.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.application.command.ApplicationResult;
import com.estoma.authorization.application.command.AssignAccountRoleCommand;
import com.estoma.authorization.application.command.CausalDependency;
import com.estoma.authorization.application.command.ChangeAccountRoleCommand;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.GrantSystemAccessCommand;
import com.estoma.authorization.application.command.ImportExecution;
import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.SystemAccessRepository;
import com.estoma.authorization.application.projection.AcademicEligibilityProjectionEvent;
import com.estoma.authorization.application.projection.ProjectionEventMetadata;
import com.estoma.authorization.application.projection.ProjectionEventProcessor;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCode;
import com.estoma.authorization.testsupport.AbstractPostgresIT;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuthorizationContractGapIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ALUMNO_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final UUID SUPERVISOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
  private static final UUID SUPERADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000205");
  private static final UUID LAVADO_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

  @Autowired private CommandDispatcher commands;
  @Autowired private AccountRoleRepository accountRoles;
  @Autowired private AccountStatusProjectionRepository accounts;
  @Autowired private PersonStatusProjectionRepository persons;
  @Autowired private SystemAccessRepository accesses;
  @Autowired private CommandIdempotencyRepository idempotency;
  @Autowired private ProcessedMessageRepository inbox;
  @Autowired private ProjectionRecoveryJobRepository recoveryJobs;
  @Autowired private ProjectionEventProcessor projectionEvents;
  @Autowired private JdbcClient jdbc;

  @Test
  void changeRoleRollsBackEveryMutationAndPersistsFailedWhenLaterDatabaseStepFails() throws Exception {
    UUID actorId = seedSuperadmin();
    UUID targetId = seedAccount(LoginType.USERNAME);
    accountRoles.insert(AccountRoleAssignment.createNew(
        targetId, SUPERVISOR_ID, RoleCode.SUPERVISOR_LAVADO, ActorRef.account(actorId), NOW));
    UUID accessId = UUID.randomUUID();
    accesses.insert(SystemAccess.createNew(
        accessId,
        targetId,
        LAVADO_ID,
        SystemCode.LAVADO_ULTRASONICO,
        ActorRef.account(actorId),
        NOW));

    installFailingSystemAccessUpdateTrigger();
    try {
      String key = "forced-technical-failure";
      var outcome = commands.dispatch(
          metadata("ChangeAccountRole", "bff", ActorRef.account(actorId), key, null),
          new ChangeAccountRoleCommand(targetId, RoleCode.ADMINISTRADOR_PRACTICAS, 1));

      assertThat(outcome.result()).get().satisfies(result -> {
        assertThat(result.status()).isEqualTo(ApplicationResult.Status.FAILED);
        assertThat(result.error()).extracting(ApplicationResult.Error::code)
            .isEqualTo("TECHNICAL_FAILURE");
      });
      assertThat(accountRoles.findByAccountId(targetId)).get()
          .extracting(AccountRoleAssignment::roleCode)
          .isEqualTo(RoleCode.SUPERVISOR_LAVADO);
      assertThat(accesses.findById(accessId)).get()
          .extracting(SystemAccess::status)
          .isEqualTo(SystemAccessStatus.ACTIVE);
      assertThat(idempotency.find("bff", "ChangeAccountRole", key)).get().satisfies(record -> {
        assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.resultStatus()).isEqualTo("FAILED");
        assertThat(record.errorCode()).isEqualTo("TECHNICAL_FAILURE");
      });
      assertThat(outboxCount(key, "EVENT")).isZero();
      assertThat(outboxCount(key, "RESULT")).isOne();
    } finally {
      removeFailingSystemAccessUpdateTrigger();
    }
  }

  @Test
  void academicGrantRejectsTargetThatIsNotAlumno() {
    UUID targetId = seedAccount(LoginType.USERNAME);
    accountRoles.insert(AccountRoleAssignment.createNew(
        targetId, SUPERVISOR_ID, RoleCode.SUPERVISOR_LAVADO, ActorRef.system("academic"), NOW));
    ImportExecution execution = ImportExecution.of(
        UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), "GRANT_STUDENT_SYSTEM_ACCESS");

    var outcome = commands.dispatch(
        metadata("GrantSystemAccess", "academic", ActorRef.system("academic"), "academic-non-student", null),
        new GrantSystemAccessCommand(
            UUID.randomUUID(), targetId, SystemCode.LAVADO_ULTRASONICO, execution));

    assertThat(outcome.result()).get().satisfies(result -> {
      assertThat(result.status()).isEqualTo(ApplicationResult.Status.REJECTED);
      assertThat(result.error()).extracting(ApplicationResult.Error::code)
          .isEqualTo("TARGET_SCOPE_NOT_ALLOWED");
      assertThat(result.dataJson()).contains("executionGeneration");
    });
  }

  @Test
  void superadminCannotGrantPracticesWithoutSupportedEligibility() {
    UUID actorId = seedSuperadmin();
    UUID targetId = seedAccount(LoginType.MATRICULA);
    accountRoles.insert(AccountRoleAssignment.createNew(
        targetId, ALUMNO_ID, RoleCode.ALUMNO, ActorRef.system("academic"), NOW));

    var outcome = commands.dispatch(
        metadata("GrantSystemAccess", "bff", ActorRef.account(actorId), "practices-without-eligibility", null),
        new GrantSystemAccessCommand(
            UUID.randomUUID(), targetId, SystemCode.PRACTICAS_PROFESIONALES, null));

    assertThat(outcome.result()).get().satisfies(result -> {
      assertThat(result.status()).isEqualTo(ApplicationResult.Status.REJECTED);
      assertThat(result.error()).extracting(ApplicationResult.Error::code)
          .isEqualTo("TARGET_SCOPE_NOT_ALLOWED");
    });
  }

  @Test
  void invalidAcademicCausationIsRejectedInsteadOfDeferred() {
    UUID targetId = UUID.randomUUID();
    UUID priorResultMessageId = UUID.randomUUID();
    ImportExecution execution = new ImportExecution(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        "ASSIGN_STUDENT_ROLE",
        List.of(new CausalDependency(
            "CreateAccount",
            priorResultMessageId,
            "SUCCEEDED",
            "AccountCreated",
            targetId,
            1)),
        List.of());

    var outcome = commands.dispatch(
        metadata(
            "AssignAccountRole",
            "academic",
            ActorRef.system("academic"),
            "bad-causation",
            UUID.randomUUID()),
        new AssignAccountRoleCommand(targetId, RoleCode.ALUMNO, execution));

    assertThat(outcome.deferred()).isFalse();
    assertThat(outcome.result()).get().satisfies(result -> {
      assertThat(result.status()).isEqualTo(ApplicationResult.Status.REJECTED);
      assertThat(result.error()).extracting(ApplicationResult.Error::code)
          .isEqualTo("ACTOR_CONTEXT_INVALID");
    });
  }

  @Test
  void eligibilityPersistsProjectionReconciliationAndStartsAccountRecoveryWhenExistenceIsKnownLocally() {
    UUID accountId = UUID.randomUUID();
    accountRoles.insert(AccountRoleAssignment.createNew(
        accountId, ALUMNO_ID, RoleCode.ALUMNO, ActorRef.system("academic"), NOW));
    UUID eligibilityId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    AcademicEligibilityProjectionEvent event = new AcademicEligibilityProjectionEvent(
        new ProjectionEventMetadata(
            UUID.randomUUID(),
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
            1),
        eligibilityId,
        profileId,
        "PRACTICAS_PROFESIONALES",
        accountId,
        true,
        1,
        1,
        1,
        NOW,
        "{}");

    assertThat(projectionEvents.process(event).status())
        .isEqualTo(ProjectionEventProcessor.Status.APPLIED);
    assertThat(jdbc.sql(
            "SELECT pending_reason FROM academic_eligibility_projection "
                + "WHERE student_academic_profile_id=:profileId AND system_code='PRACTICAS_PROFESIONALES'")
        .param("profileId", profileId)
        .query(String.class)
        .single())
        .isEqualTo("PROJECTION_RECONCILIATION");
    assertThat(recoveryJobs.findActive(
            "ACCOUNT_STATUS", "ENTITY", "account:" + accountId))
        .isPresent();
  }

  @Test
  void deferredInboxClaimIsFencedByToken() {
    UUID messageId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    ProcessedMessageRepository.ProcessedMessage message =
        new ProcessedMessageRepository.ProcessedMessage(
            "authorization-events",
            messageId,
            "DEFERRED",
            "AccountActivated",
            1,
            "EVENT",
            "authentication",
            UUID.randomUUID(),
            null,
            null,
            null,
            aggregateId,
            2,
            ActorRef.system("authentication"),
            "{}",
            NOW,
            NOW,
            null,
            "EVENT_PENDING",
            NOW);
    assertThat(inbox.insertIfAbsent(message)).isTrue();

    UUID firstToken = UUID.randomUUID();
    UUID staleToken = UUID.randomUUID();
    assertThat(inbox.claimDeferred(
            "authorization-events", messageId, firstToken, NOW.plusSeconds(30), NOW))
        .isTrue();
    assertThat(inbox.claimDeferred(
            "authorization-events", messageId, staleToken, NOW.plusSeconds(30), NOW.plusSeconds(1)))
        .isFalse();
    assertThat(inbox.markAppliedClaimed(
            "authorization-events", messageId, staleToken, NOW.plusSeconds(2), NOW.plusSeconds(2)))
        .isZero();
    assertThat(inbox.markAppliedClaimed(
            "authorization-events", messageId, firstToken, NOW.plusSeconds(2), NOW.plusSeconds(2)))
        .isOne();
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
      String type,
      String producer,
      ActorRef actor,
      String idempotencyKey,
      UUID causationId) {
    return new CommandMetadata(
        UUID.randomUUID(),
        type,
        1,
        producer,
        NOW,
        UUID.randomUUID(),
        causationId,
        UUID.randomUUID(),
        idempotencyKey,
        actor,
        null,
        null);
  }

  private long outboxCount(String idempotencyKey, String kind) {
    return jdbc.sql(
            "SELECT count(*) FROM outbox_messages "
                + "WHERE idempotency_key=:key AND message_kind=:kind")
        .param("key", idempotencyKey)
        .param("kind", kind)
        .query(Long.class)
        .single();
  }

  private void installFailingSystemAccessUpdateTrigger() throws SQLException {
    try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS authorization_test_fail_access_update ON system_accesses");
      statement.execute("DROP FUNCTION IF EXISTS authorization_test_fail_access_update()");
      statement.execute(
          "CREATE FUNCTION authorization_test_fail_access_update() RETURNS trigger LANGUAGE plpgsql AS $$ "
              + "BEGIN RAISE EXCEPTION 'forced system access update failure'; END $$");
      statement.execute(
          "CREATE TRIGGER authorization_test_fail_access_update BEFORE UPDATE ON system_accesses "
              + "FOR EACH ROW EXECUTE FUNCTION authorization_test_fail_access_update()");
    }
  }

  private void removeFailingSystemAccessUpdateTrigger() throws SQLException {
    try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS authorization_test_fail_access_update ON system_accesses");
      statement.execute("DROP FUNCTION IF EXISTS authorization_test_fail_access_update()");
    }
  }
}
