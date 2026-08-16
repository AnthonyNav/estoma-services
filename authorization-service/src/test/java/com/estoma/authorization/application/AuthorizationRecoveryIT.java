package com.estoma.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authorization.application.command.AssignAccountRoleCommand;
import com.estoma.authorization.application.command.AuthoritativeEvidence;
import com.estoma.authorization.application.command.CausalDependency;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.CommandOutcome;
import com.estoma.authorization.application.command.ImportExecution;
import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.projection.AccountProjectionEvent;
import com.estoma.authorization.application.projection.ProjectionEventMetadata;
import com.estoma.authorization.application.projection.ProjectionEventProcessor;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator.ProjectionType;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.AccountSnapshot;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResultHandler;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuthorizationRecoveryIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID SUPERADMIN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000205");

  @Autowired private CommandDispatcher commands;
  @Autowired private ProjectionEventProcessor projectionEvents;
  @Autowired private ProjectionRecoveryCoordinator recovery;
  @Autowired private ProjectionRecoveryResultHandler recoveryResults;
  @Autowired private ProjectionRecoveryJobRepository recoveryJobs;
  @Autowired private AccountStatusProjectionRepository accounts;
  @Autowired private PersonStatusProjectionRepository persons;
  @Autowired private AccountRoleRepository accountRoles;
  @Autowired private CommandIdempotencyRepository idempotency;
  @Autowired private JdbcClient jdbc;

  @Test
  void exactRedeliveryIsSilentButNewDeliveryOfSameIntentPublishesOnlyAnotherResult() {
    UUID actorId = seedSuperadmin();
    UUID targetId = seedAccount(LoginType.USERNAME, ProjectedStatus.ACTIVE, 1);
    String key = "delivery-replay-key";
    AssignAccountRoleCommand command =
        new AssignAccountRoleCommand(targetId, RoleCode.ADMINISTRADOR_PRACTICAS, null);
    CommandMetadata firstMetadata = metadata(
        UUID.randomUUID(), "AssignAccountRole", "bff", ActorRef.account(actorId), key);

    CommandOutcome first = commands.dispatch(firstMetadata, command);
    assertThat(first.result()).isPresent();
    assertThat(outboxCount(key, "EVENT")).isEqualTo(1);
    assertThat(outboxCount(key, "RESULT")).isEqualTo(1);

    CommandOutcome exactRedelivery = commands.dispatch(firstMetadata, command);
    assertThat(exactRedelivery.duplicateDelivery()).isTrue();
    assertThat(outboxCount(key, "EVENT")).isEqualTo(1);
    assertThat(outboxCount(key, "RESULT")).isEqualTo(1);

    CommandMetadata newDelivery = metadata(
        UUID.randomUUID(), "AssignAccountRole", "bff", ActorRef.account(actorId), key);
    CommandOutcome replay = commands.dispatch(newDelivery, command);
    assertThat(replay.result()).isPresent();
    assertThat(replay.events()).isEmpty();
    assertThat(outboxCount(key, "EVENT")).isEqualTo(1);
    assertThat(outboxCount(key, "RESULT")).isEqualTo(2);
  }

  @Test
  void accountStatusTransitionCanArriveBeforeCreatedAndReplaysWithPersistedLoginType() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    AccountProjectionEvent activated = accountEvent(
        UUID.randomUUID(),
        "AccountActivated",
        accountId,
        personId,
        null,
        ProjectedStatus.ACTIVE,
        2,
        correlationId,
        "{\"accountId\":\"" + accountId + "\",\"personId\":\"" + personId
            + "\",\"status\":\"ACTIVE\"}");

    assertThat(projectionEvents.process(activated).status())
        .isEqualTo(ProjectionEventProcessor.Status.DEFERRED);
    assertThat(projectionEvents.process(activated).status())
        .isEqualTo(ProjectionEventProcessor.Status.DEFERRED);

    AccountProjectionEvent created = accountEvent(
        UUID.randomUUID(),
        "AccountCreated",
        accountId,
        personId,
        LoginType.USERNAME,
        ProjectedStatus.INACTIVE,
        1,
        correlationId,
        "{\"accountId\":\"" + accountId + "\",\"personId\":\"" + personId
            + "\",\"loginType\":\"USERNAME\",\"status\":\"INACTIVE\"}");
    assertThat(projectionEvents.process(created).status())
        .isEqualTo(ProjectionEventProcessor.Status.APPLIED);

    assertThat(accounts.findByAccountId(accountId)).get().satisfies(projected -> {
      assertThat(projected.loginType()).isEqualTo(LoginType.USERNAME);
      assertThat(projected.status()).isEqualTo(ProjectedStatus.ACTIVE);
      assertThat(projected.sourceVersion()).isEqualTo(2);
    });
  }

  @Test
  void accountCreatedWakesTheSameDeferredAcademicCommandWithoutASecondIntent() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    UUID commandMessageId = UUID.randomUUID();
    UUID priorResultMessageId = UUID.randomUUID();
    UUID importId = UUID.randomUUID();
    UUID planItemId = UUID.randomUUID();
    String key = "academic-event-pending-key";
    ImportExecution execution = new ImportExecution(
        importId,
        planItemId,
        1,
        UUID.randomUUID(),
        "ASSIGN_STUDENT_ROLE",
        List.of(new CausalDependency(
            "CreateAccount",
            priorResultMessageId,
            "SUCCEEDED",
            "AccountCreated",
            accountId,
            1)),
        List.of());
    CommandMetadata commandMetadata = metadata(
        commandMessageId,
        "AssignAccountRole",
        "academic",
        ActorRef.system("academic"),
        key,
        priorResultMessageId);

    CommandOutcome deferred = commands.dispatch(
        commandMetadata,
        new AssignAccountRoleCommand(accountId, RoleCode.ALUMNO, execution));
    assertThat(deferred.deferred()).isTrue();
    assertThat(deferred.pendingReason()).isEqualTo("EVENT_PENDING");

    AccountProjectionEvent created = accountEvent(
        UUID.randomUUID(),
        "AccountCreated",
        accountId,
        personId,
        LoginType.MATRICULA,
        ProjectedStatus.INACTIVE,
        1,
        UUID.randomUUID(),
        "{\"accountId\":\"" + accountId + "\",\"personId\":\"" + personId
            + "\",\"loginType\":\"MATRICULA\",\"status\":\"INACTIVE\"}");
    projectionEvents.process(created);

    assertThat(accountRoles.findByAccountId(accountId)).get()
        .extracting(AccountRoleAssignment::roleCode)
        .isEqualTo(RoleCode.ALUMNO);
    assertThat(idempotency.find("academic", "AssignAccountRole", key)).get()
        .satisfies(record -> {
          assertThat(record.status()).isEqualTo("COMPLETED");
          assertThat(record.resultStatus()).isEqualTo("SUCCEEDED");
          assertThat(record.resultDataJson())
              .contains(importId.toString(), planItemId.toString(), "\"executionGeneration\":1");
        });
    assertThat(
            jdbc.sql(
                    "SELECT status::text FROM processed_messages "
                        + "WHERE consumer_name='authorization-commands' AND message_id=:messageId")
                .param("messageId", commandMessageId)
                .query(String.class)
                .single())
        .isEqualTo("APPLIED");
  }

  @Test
  void projectionReconciliationCreatesEntityRecoveryAndNotFoundTerminatesDeferredCommand() {
    UUID accountId = UUID.randomUUID();
    String key = "academic-reconcile-key";
    ImportExecution execution = new ImportExecution(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        "ASSIGN_STUDENT_ROLE",
        List.of(),
        List.of(new AuthoritativeEvidence(
            "authentication",
            "CreateAccount",
            "SUCCEEDED",
            UUID.randomUUID(),
            "Account",
            accountId,
            1)));
    CommandOutcome deferred = commands.dispatch(
        metadata(
            UUID.randomUUID(),
            "AssignAccountRole",
            "academic",
            ActorRef.system("academic"),
            key),
        new AssignAccountRoleCommand(accountId, RoleCode.ALUMNO, execution));

    assertThat(deferred.pendingReason()).isEqualTo("PROJECTION_RECONCILIATION");
    var job = recoveryJobs.findActive(
            "ACCOUNT_STATUS", "ENTITY", "account:" + accountId)
        .orElseThrow();
    assertThat(job.status()).isEqualTo("WAITING_RESULT");

    ProjectionRecoveryResult notFound = new ProjectionRecoveryResult(
        recoveryMetadata(job, "authentication"),
        ProjectionRecoveryResult.Status.NOT_FOUND,
        List.of(),
        null,
        false,
        null);
    assertThat(recoveryResults.handle(notFound).status())
        .isEqualTo(ProjectionRecoveryResultHandler.Status.NOT_FOUND_CONFIRMED);
    assertThat(idempotency.find("academic", "AssignAccountRole", key)).get()
        .satisfies(record -> {
          assertThat(record.status()).isEqualTo("COMPLETED");
          assertThat(record.resultStatus()).isEqualTo("REJECTED");
          assertThat(record.errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
          assertThat(record.resultDataJson()).contains("executionGeneration");
        });
  }

  @Test
  void entityNotFoundNeverDeletesConcurrentLocalProjectionAndKeepsEntityUntrusted() {
    UUID accountId = seedAccount(LoginType.USERNAME, ProjectedStatus.ACTIVE, 3);
    var job = recovery.requestAccount(accountId);

    ProjectionRecoveryResult notFound = new ProjectionRecoveryResult(
        recoveryMetadata(job, "authentication"),
        ProjectionRecoveryResult.Status.NOT_FOUND,
        List.of(),
        null,
        false,
        null);
    assertThat(recoveryResults.handle(notFound).status())
        .isEqualTo(ProjectionRecoveryResultHandler.Status.NOT_FOUND_CONFLICT);
    assertThat(accounts.findByAccountId(accountId)).isPresent();
    assertThat(recoveryJobs.isEntityTrusted("ACCOUNT_STATUS", "account:" + accountId)).isFalse();
  }

  @Test
  void entityFoundForAnotherAccountIsRejectedBeforeProjectionMutation() {
    UUID requestedAccountId = UUID.randomUUID();
    UUID wrongAccountId = UUID.randomUUID();
    UUID wrongPersonId = UUID.randomUUID();
    var job = recovery.requestAccount(requestedAccountId);

    ProjectionRecoveryResult mismatched = new ProjectionRecoveryResult(
        recoveryMetadata(job, "authentication"),
        ProjectionRecoveryResult.Status.FOUND,
        List.of(new AccountSnapshot(
            wrongAccountId,
            wrongPersonId,
            LoginType.MATRICULA,
            ProjectedStatus.INACTIVE,
            4,
            NOW.plusSeconds(1))),
        null,
        false,
        null);

    assertThatThrownBy(() -> recoveryResults.handle(mismatched))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("snapshot identity");
    assertThat(accounts.findByAccountId(wrongAccountId)).isEmpty();
    assertThat(recoveryJobs.findById(job.recoveryJobId())).get()
        .extracting(ProjectionRecoveryJobRepository.RecoveryJob::status)
        .isEqualTo("WAITING_RESULT");
  }

  @Test
  void fullAccountRebuildStartsEmptyAndCompletesWithoutGhostRows() {
    UUID ghostId = seedAccount(LoginType.USERNAME, ProjectedStatus.ACTIVE, 1);
    UUID rebuiltId = UUID.randomUUID();
    UUID rebuiltPersonId = UUID.randomUUID();

    var job = recovery.requestFull(ProjectionType.ACCOUNT_STATUS);
    assertThat(accounts.findByAccountId(ghostId)).isEmpty();
    assertThat(recoveryJobs.isProjectionTrusted("ACCOUNT_STATUS")).isFalse();

    ProjectionRecoveryResult page = new ProjectionRecoveryResult(
        recoveryMetadata(job, "authentication"),
        ProjectionRecoveryResult.Status.SUCCEEDED,
        List.of(new AccountSnapshot(
            rebuiltId,
            rebuiltPersonId,
            LoginType.MATRICULA,
            ProjectedStatus.INACTIVE,
            4,
            NOW.plusSeconds(1))),
        null,
        false,
        null);
    assertThat(recoveryResults.handle(page).status())
        .isEqualTo(ProjectionRecoveryResultHandler.Status.COMPLETED);

    assertThat(accounts.findByAccountId(ghostId)).isEmpty();
    assertThat(accounts.findByAccountId(rebuiltId)).get().satisfies(projected -> {
      assertThat(projected.sourceVersion()).isEqualTo(4);
      assertThat(projected.loginType()).isEqualTo(LoginType.MATRICULA);
    });
    assertThat(recoveryJobs.isProjectionTrusted("ACCOUNT_STATUS")).isTrue();
  }

  private UUID seedSuperadmin() {
    UUID actorId = seedAccount(LoginType.USERNAME, ProjectedStatus.ACTIVE, 1);
    accountRoles.insert(AccountRoleAssignment.createNew(
        actorId,
        SUPERADMIN_ID,
        RoleCode.SUPERADMIN,
        ActorRef.account(actorId),
        NOW));
    return actorId;
  }

  private UUID seedAccount(LoginType loginType, ProjectedStatus status, int sourceVersion) {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    persons.upsertIfNewer(new PersonStatusProjectionRepository.PersonStatusProjection(
        personId, ProjectedStatus.ACTIVE, sourceVersion, NOW));
    accounts.upsertIfNewer(new AccountStatusProjectionRepository.AccountStatusProjection(
        accountId, personId, loginType, status, sourceVersion, NOW));
    return accountId;
  }

  private static AccountProjectionEvent accountEvent(
      UUID messageId,
      String type,
      UUID accountId,
      UUID personId,
      LoginType loginType,
      ProjectedStatus status,
      int version,
      UUID correlationId,
      String payload) {
    return new AccountProjectionEvent(
        new ProjectionEventMetadata(
            messageId,
            type,
            1,
            "authentication",
            NOW,
            correlationId,
            null,
            null,
            null,
            ActorRef.system("authentication"),
            accountId,
            version),
        accountId,
        personId,
        loginType,
        status,
        payload);
  }

  private static CommandMetadata metadata(
      UUID messageId,
      String type,
      String producer,
      ActorRef actor,
      String idempotencyKey) {
    return CommandMetadata.create(
        messageId,
        type,
        producer,
        UUID.randomUUID(),
        UUID.randomUUID(),
        idempotencyKey,
        actor,
        NOW);
  }

  private static CommandMetadata metadata(
      UUID messageId,
      String type,
      String producer,
      ActorRef actor,
      String idempotencyKey,
      UUID causationId) {
    return new CommandMetadata(
        messageId,
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

  private static ProjectionRecoveryResult.Metadata recoveryMetadata(
      ProjectionRecoveryJobRepository.RecoveryJob job, String producer) {
    return new ProjectionRecoveryResult.Metadata(
        UUID.randomUUID(),
        job.currentQueryType() + "Result",
        producer,
        NOW.plusSeconds(2),
        job.currentCorrelationId(),
        job.currentQueryMessageId(),
        job.currentOperationId(),
        ActorRef.system(producer));
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
}
