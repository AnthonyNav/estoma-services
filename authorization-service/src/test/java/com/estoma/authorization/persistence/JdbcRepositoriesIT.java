package com.estoma.authorization.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.SystemAccessRepository;
import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemAccessReasonCode;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCode;
import com.estoma.authorization.infrastructure.persistence.Timestamps;
import com.estoma.authorization.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcRepositoriesIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID LAVADO_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID ALUMNO_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

  @Autowired private SystemCatalogRepository systems;
  @Autowired private AccountRoleRepository accountRoles;
  @Autowired private SystemAccessRepository accesses;
  @Autowired private PersonStatusProjectionRepository persons;
  @Autowired private AccountStatusProjectionRepository accounts;
  @Autowired private com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository eligibility;
  @Autowired private ProcessedMessageRepository inbox;
  @Autowired private CommandIdempotencyRepository idempotency;
  @Autowired private OutboxRepository outbox;
  @Autowired private com.estoma.authorization.application.port.AuthorizationAuditRepository audit;
  @Autowired private ProjectionRecoveryJobRepository recoveryJobs;
  @Autowired private TransactionRunner transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  void catalogAndAggregateRepositoriesRoundTripWithOptimisticLocking() {
    UUID accountId = UUID.randomUUID();
    AccountRoleAssignment assignment =
        AccountRoleAssignment.createNew(
            accountId, ALUMNO_ID, RoleCode.ALUMNO, ActorRef.system("academic"), NOW);
    accountRoles.insert(assignment);

    assertThat(systems.findByCode("LAVADO_ULTRASONICO")).isPresent();
    assertThat(accountRoles.findByAccountId(accountId)).get().satisfies(found -> {
      assertThat(found.roleCode()).isEqualTo(RoleCode.ALUMNO);
      assertThat(found.version()).isOne();
    });

    AccountRoleAssignment changed =
        AccountRoleAssignment.reconstitute(
            accountId,
            UUID.fromString("00000000-0000-0000-0000-000000000205"),
            RoleCode.SUPERADMIN,
            2,
            assignment.assignedBy(),
            ActorRef.account(UUID.randomUUID()),
            assignment.assignedAt(),
            NOW.plusSeconds(1));
    assertThat(accountRoles.update(changed, 1)).isOne();
    assertThat(accountRoles.update(changed, 1)).isZero();

    UUID accessId = UUID.randomUUID();
    SystemAccess access =
        SystemAccess.createNew(
            accessId,
            accountId,
            LAVADO_ID,
            SystemCode.LAVADO_ULTRASONICO,
            ActorRef.account(UUID.randomUUID()),
            NOW);
    accesses.insert(access);
    SystemAccess suspended =
        access.suspend(SystemAccessReasonCode.ADMINISTRATIVE, null, ActorRef.account(UUID.randomUUID()), NOW.plusSeconds(1));
    assertThat(accesses.update(suspended, 1)).isOne();
    assertThat(accesses.findByAccountId(accountId)).extracting(SystemAccess::status)
        .containsExactly(SystemAccessStatus.SUSPENDED);
  }

  @Test
  void projectionsAreMonotonicAndTechnicalRepositoriesPersistState() {
    UUID personId = UUID.randomUUID();
    assertThat(persons.upsertIfNewer(
            new PersonStatusProjectionRepository.PersonStatusProjection(
                personId, ProjectedStatus.ACTIVE, 2, NOW)))
        .isOne();
    assertThat(persons.upsertIfNewer(
            new PersonStatusProjectionRepository.PersonStatusProjection(
                personId, ProjectedStatus.INACTIVE, 1, NOW.plusSeconds(1))))
        .isZero();

    UUID accountId = UUID.randomUUID();
    assertThat(accounts.upsertIfNewer(
            new AccountStatusProjectionRepository.AccountStatusProjection(
                accountId, personId, LoginType.MATRICULA, ProjectedStatus.ACTIVE, 3, NOW)))
        .isOne();

    UUID eligibilityId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    assertThat(eligibility.upsertIfNewer(
            new AcademicEligibilityProjectionRepository.AcademicEligibilityProjection(
                eligibilityId,
                profileId,
                "PRACTICAS_PROFESIONALES",
                accountId,
                true,
                1,
                4,
                8,
                "EVENT_PENDING",
                NOW,
                NOW)))
        .isOne();
    assertThat(eligibility.upsertIfNewer(
            new AcademicEligibilityProjectionRepository.AcademicEligibilityProjection(
                UUID.randomUUID(),
                profileId,
                "PRACTICAS_PROFESIONALES",
                accountId,
                false,
                1,
                3,
                7,
                null,
                NOW.plusSeconds(1),
                NOW.plusSeconds(1))))
        .isZero();

    UUID messageId = UUID.randomUUID();
    ProcessedMessageRepository.ProcessedMessage message =
        new ProcessedMessageRepository.ProcessedMessage(
            "authorization-events", messageId, "DEFERRED", "PersonCreated", 1, "EVENT", "users",
            UUID.randomUUID(), null, null, null, personId, 2, ActorRef.system("users"), "{}", NOW, NOW, null,
            "EVENT_PENDING", NOW);
    assertThat(inbox.insertIfAbsent(message)).isTrue();
    assertThat(inbox.insertIfAbsent(message)).isFalse();
    assertThat(inbox.markApplied("authorization-events", messageId, NOW.plusSeconds(1), NOW.plusSeconds(1)))
        .isOne();

    CommandIdempotencyRepository.IdempotencyRecord record =
        new CommandIdempotencyRepository.IdempotencyRecord(
            "bff", "AssignAccountRole", "key-1", "f".repeat(64), "DEFERRED", null, null, null, null,
            null, null, null, null, null, "EVENT_PENDING", NOW, null, NOW);
    assertThat(idempotency.insertIfAbsent(record)).isTrue();
    assertThat(idempotency.find("bff", "AssignAccountRole", "key-1")).get()
        .extracting(CommandIdempotencyRepository.IdempotencyRecord::status)
        .isEqualTo("DEFERRED");
    assertThat(idempotency.update(record)).isZero();

    CommandIdempotencyRepository.IdempotencyRecord completed =
        new CommandIdempotencyRepository.IdempotencyRecord(
            "bff", "AssignAccountRole", "key-1", "f".repeat(64), "COMPLETED", "SUCCEEDED", "{}", null, null,
            null, null, null, null, null, null, NOW, NOW.plusSeconds(1), NOW.plusSeconds(1));
    assertThat(idempotency.update(completed)).isOne();
    assertThat(idempotency.find("bff", "AssignAccountRole", "key-1")).get()
        .extracting(CommandIdempotencyRepository.IdempotencyRecord::status)
        .isEqualTo("COMPLETED");

    CommandIdempotencyRepository.IdempotencyRecord terminalOverwrite =
        new CommandIdempotencyRepository.IdempotencyRecord(
            "bff", "AssignAccountRole", "key-1", "f".repeat(64), "FAILED", "FAILED", null, "TECHNICAL", "{}",
            null, null, null, null, null, null, NOW, NOW.plusSeconds(2), NOW.plusSeconds(2));
    assertThat(idempotency.update(terminalOverwrite)).isZero();

    CommandIdempotencyRepository.IdempotencyRecord differentRequest =
        new CommandIdempotencyRepository.IdempotencyRecord(
            "bff", "AssignAccountRole", "key-1", "different", "COMPLETED", "SUCCEEDED", "{}", null, null,
            null, null, null, null, null, null, NOW, NOW.plusSeconds(3), NOW.plusSeconds(3));
    assertThat(idempotency.update(differentRequest)).isZero();

    UUID outboxId = UUID.randomUUID();
    outbox.append(
        new OutboxRepository.OutboxMessage(
            outboxId, "AccountRoleAssigned", 1, "EVENT", "authorization",
            "authorization.account-role.assigned", UUID.randomUUID(), "key-1", ActorRef.system("authorization"),
            accountId, 1, UUID.randomUUID(), messageId, "{}", "PENDING", 0, NOW, NOW, null, null, null, null, null, null));
    assertThat(outbox.findById(outboxId)).isPresent();

    UUID recoveryId = UUID.randomUUID();
    recoveryJobs.insert(
        new ProjectionRecoveryJobRepository.RecoveryJob(
            recoveryId,
            "PERSON_STATUS",
            "ENTITY",
            "person:" + personId,
            "PENDING",
            null,
            0,
            null,
            null,
            null,
            null,
            0,
            null,
            NOW,
            NOW,
            null,
            null,
            null));
    assertThat(recoveryJobs.findById(recoveryId)).get()
        .extracting(ProjectionRecoveryJobRepository.RecoveryJob::entityKey)
        .isEqualTo("person:" + personId);

    audit.append(
        new com.estoma.authorization.application.port.AuthorizationAuditRepository.AuditEntry(
            UUID.randomUUID(),
            messageId,
            "users",
            "Person",
            personId,
            2,
            "PERSON_CREATED",
            ActorRef.system("users"),
            null,
            null,
            message.correlationId(),
            null,
            null,
            null,
            "SUCCEEDED",
            null,
            null,
            NOW));
    assertThat(jdbc.sql("SELECT count(*) FROM authorization_audit_entries WHERE source_message_id = :messageId")
            .param("messageId", messageId)
            .query(Long.class)
            .single())
        .isOne();
  }

  @Test
  void transactionRunnerCommitsAndRollsBackAsOneUnit() {
    UUID accountId = UUID.randomUUID();
    transactions.inTransaction(
        () ->
            jdbc.sql(
                    "INSERT INTO account_status_projection (account_id, person_id, login_type, status, source_version, updated_at) "
                        + "VALUES (:accountId, :personId, 'MATRICULA', 'ACTIVE', 1, :updatedAt)")
                .param("accountId", accountId)
                .param("personId", UUID.randomUUID())
                .param("updatedAt", Timestamps.toTimestamp(NOW))
                .update());

    assertThat(accounts.findByAccountId(accountId)).isPresent();
    UUID rolledBackId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                transactions.inTransaction(
                    () -> {
                      jdbc.sql(
                              "INSERT INTO account_status_projection (account_id, person_id, login_type, status, source_version, updated_at) "
                                  + "VALUES (:accountId, :personId, 'MATRICULA', 'ACTIVE', 1, :updatedAt)")
                          .param("accountId", rolledBackId)
                          .param("personId", UUID.randomUUID())
                          .param("updatedAt", Timestamps.toTimestamp(NOW))
                          .update();
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(accounts.findByAccountId(rolledBackId)).isEmpty();
  }
}
