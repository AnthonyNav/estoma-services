package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.domain.idempotency.IdempotencyRecord;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import com.estoma.platformbff.domain.messaging.OutboxMessage;
import com.estoma.platformbff.domain.messaging.OutboxStatus;
import com.estoma.platformbff.domain.operation.ClientOperation;
import com.estoma.platformbff.domain.operation.OperationStatus;
import com.estoma.platformbff.domain.projection.ProjectionState;
import com.estoma.platformbff.domain.projection.ProjectionStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Tag("integration")
class BffPostgresMigrationIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_db")
      .withUsername("bff")
      .withPassword("bff");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired JdbcClient jdbc;
  @Autowired JdbcClientOperationRepository operations;
  @Autowired JdbcIdempotencyRepository idempotency;
  @Autowired JdbcInboxRepository inbox;
  @Autowired JdbcOutboxRepository outbox;
  @Autowired JdbcProjectionStatusRepository projectionStatus;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void clearMutableTables() {
    jdbc.sql("""
        TRUNCATE TABLE
          inbox_messages,
          outbox_messages,
          idempotency_records,
          client_operations,
          projection_recovery_jobs,
          projection_status,
          account_person_projection,
          session_revocations,
          account_session_revocation_cutoffs,
          person_session_revocation_cutoffs,
          authorization_role_projection,
          authorization_access_projection
        CASCADE
        """).update();
  }

  @Test
  void createsAllTechnicalAndSecurityTables() {
    var count = jdbc.sql("""
        SELECT count(*) FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name IN (
            'client_operations', 'idempotency_records', 'outbox_messages',
            'inbox_messages', 'projection_status', 'projection_recovery_jobs',
            'account_person_projection', 'session_revocations',
            'account_session_revocation_cutoffs', 'person_session_revocation_cutoffs',
            'authorization_role_projection', 'authorization_access_projection')
        """).query(Integer.class).single();
    assertThat(count).isEqualTo(12);
  }

  @Test
  void authorizationAccessProjectionKeepsCanonicalAggregateIdentity() {
    var columns = jdbc.sql("""
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'authorization_access_projection'
        """).query(String.class).list();

    assertThat(columns).contains("system_access_id", "account_id", "system_id", "system_code",
        "status", "access_source_version", "updated_at");

    var nullableColumns = jdbc.sql("""
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'authorization_access_projection'
          AND is_nullable = 'YES'
        """).query(String.class).list();
    assertThat(nullableColumns).isEmpty();
  }

  @Test
  void clientOperationCanOnlyCompleteFromPendingWithTerminalState() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    var pending = pendingOperation(UUID.randomUUID(), now);
    operations.insert(pending);

    assertThatThrownBy(() -> operations.completeIfPending(pending.operationId(), pending, now.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);

    var succeeded = pending.withTerminalResult(OperationStatus.SUCCEEDED, "{\"ok\":true}",
        null, null, now.plusSeconds(2));
    assertThat(operations.completeIfPending(pending.operationId(), succeeded, now.plusSeconds(2))).isTrue();

    var failed = pending.withTerminalResult(OperationStatus.FAILED, null,
        "OWNER_FAILED", "{\"reason\":\"test\"}", now.plusSeconds(3));
    assertThat(operations.completeIfPending(pending.operationId(), failed, now.plusSeconds(3))).isFalse();
    assertThat(operations.findById(pending.operationId()).orElseThrow().status())
        .isEqualTo(OperationStatus.SUCCEEDED);
  }

  @Test
  void idempotencyLogicalScopeIsUniqueEvenWhenScopeKeyDiffers() {
    Instant now = Instant.now();
    UUID actorAccountId = UUID.randomUUID();
    var firstOperation = pendingOperation(UUID.randomUUID(), actorAccountId, "same-key", now);
    var secondOperation = pendingOperation(UUID.randomUUID(), actorAccountId, "same-key", now);
    operations.insert(firstOperation);
    operations.insert(secondOperation);

    idempotency.insert(new IdempotencyRecord(
        "scope-a",
        actorAccountId,
        firstOperation.publicOperation(),
        "same-key",
        "fingerprint-a",
        firstOperation.operationId(),
        now,
        now.plus(24, ChronoUnit.HOURS)));

    var duplicateLogicalScope = new IdempotencyRecord(
        "scope-b",
        actorAccountId,
        secondOperation.publicOperation(),
        "same-key",
        "fingerprint-b",
        secondOperation.operationId(),
        now,
        now.plus(24, ChronoUnit.HOURS));

    assertThatThrownBy(() -> idempotency.insert(duplicateLogicalScope))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void idempotencyLogicalScopeTreatsNullActorAsSameScope() {
    Instant now = Instant.now();
    var firstOperation = pendingOperation(UUID.randomUUID(), null, "public-key", now);
    var secondOperation = pendingOperation(UUID.randomUUID(), null, "public-key", now);
    operations.insert(firstOperation);
    operations.insert(secondOperation);

    idempotency.insert(new IdempotencyRecord(
        "public-scope-a",
        null,
        firstOperation.publicOperation(),
        "public-key",
        "fingerprint-a",
        firstOperation.operationId(),
        now,
        now.plus(24, ChronoUnit.HOURS)));

    var duplicateLogicalScope = new IdempotencyRecord(
        "public-scope-b",
        null,
        secondOperation.publicOperation(),
        "public-key",
        "fingerprint-b",
        secondOperation.operationId(),
        now,
        now.plus(24, ChronoUnit.HOURS));

    assertThatThrownBy(() -> idempotency.insert(duplicateLogicalScope))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void inboxDeduplicationDoesNotAbortSurroundingTransaction() {
    Instant now = Instant.now();
    UUID messageId = UUID.randomUUID();
    var first = inboxMessage("results.bff", messageId, now);
    var transaction = new TransactionTemplate(transactionManager);

    transaction.executeWithoutResult(ignored -> {
      assertThat(inbox.recordIfNew(first)).isTrue();
      assertThat(inbox.recordIfNew(first)).isFalse();
      assertThat(inbox.recordIfNew(inboxMessage("events.bff-projections", messageId, now))).isTrue();

      var count = jdbc.sql("""
          SELECT count(*) FROM inbox_messages WHERE message_id = :messageId
          """)
          .param("messageId", messageId)
          .query(Integer.class)
          .single();
      assertThat(count).isEqualTo(2);
    });
  }

  @Test
  void outboxPersistsAggregateMetadataNeededForDeterministicEnvelopeReconstruction() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    var operation = pendingOperation(UUID.randomUUID(), now);
    operations.insert(operation);
    var message = pendingOutbox(operation, UUID.randomUUID(), now);
    outbox.insert(message);

    var claimed = outbox.claimBatch("worker-a", now, 1).get(0);
    assertThat(claimed.aggregateId()).isEqualTo(message.aggregateId());
    assertThat(claimed.aggregateVersion()).isEqualTo(message.aggregateVersion());
  }

  @Test
  void failedOutboxMessageIsRetryableWhenNextAttemptIsDue() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    var operation = pendingOperation(UUID.randomUUID(), now);
    operations.insert(operation);
    var message = pendingOutbox(operation, UUID.randomUUID(), now);
    outbox.insert(message);

    var firstClaim = outbox.claimBatch("worker-a", now, 1);
    assertThat(firstClaim).hasSize(1);
    assertThat(firstClaim.get(0).attemptCount()).isEqualTo(1);

    Instant retryAt = now.plusSeconds(2);
    assertThat(outbox.markFailed(message.messageId(), firstClaim.get(0).claimToken(),
        "PUBLISH_FAILED", retryAt)).isTrue();
    assertThat(outbox.claimBatch("worker-b", retryAt.minusMillis(1), 1)).isEmpty();

    var retryClaim = outbox.claimBatch("worker-b", retryAt.plusMillis(1), 1);
    assertThat(retryClaim).hasSize(1);
    assertThat(retryClaim.get(0).messageId()).isEqualTo(message.messageId());
    assertThat(retryClaim.get(0).attemptCount()).isEqualTo(2);
    assertThat(retryClaim.get(0).aggregateId()).isEqualTo(message.aggregateId());
    assertThat(retryClaim.get(0).aggregateVersion()).isEqualTo(message.aggregateVersion());
  }

  @Test
  void failedOutboxWithoutNextAttemptRemainsQuarantined() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    var operation = pendingOperation(UUID.randomUUID(), now);
    operations.insert(operation);
    var message = pendingOutbox(operation, UUID.randomUUID(), now);
    outbox.insert(message);

    var claim = outbox.claimBatch("worker-a", now, 1).get(0);
    assertThat(outbox.markFailed(message.messageId(), claim.claimToken(), "QUARANTINED", null)).isTrue();
    assertThat(outbox.claimBatch("worker-b", now.plusSeconds(60), 1)).isEmpty();
  }

  @Test
  void outboxClaimIsSafeUnderConcurrentWorkers() throws Exception {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    var operation = pendingOperation(UUID.randomUUID(), now);
    operations.insert(operation);
    outbox.insert(pendingOutbox(operation, UUID.randomUUID(), now));

    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    Callable<List<OutboxMessage>> claim = () -> {
      ready.countDown();
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("claim workers did not start together");
      }
      return outbox.claimBatch(UUID.randomUUID().toString(), now, 1);
    };

    try {
      var first = pool.submit(claim);
      var second = pool.submit(claim);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      int claimed = first.get(5, TimeUnit.SECONDS).size() + second.get(5, TimeUnit.SECONDS).size();
      assertThat(claimed).isEqualTo(1);
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void projectionStatusDoesNotRegressToOlderGeneration() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    projectionStatus.upsert(new ProjectionState(
        "authorization", ProjectionStatus.READY, now, null, 2, now));
    projectionStatus.upsert(new ProjectionState(
        "authorization", ProjectionStatus.DEGRADED, now.plusSeconds(1), "STALE", 1,
        now.plusSeconds(1)));

    var stored = projectionStatus.findByName("authorization").orElseThrow();
    assertThat(stored.generation()).isEqualTo(2);
    assertThat(stored.status()).isEqualTo(ProjectionStatus.READY);

    projectionStatus.upsert(new ProjectionState(
        "authorization", ProjectionStatus.DEGRADED, now.plusSeconds(2), "CURRENT", 2,
        now.plusSeconds(2)));
    stored = projectionStatus.findByName("authorization").orElseThrow();
    assertThat(stored.generation()).isEqualTo(2);
    assertThat(stored.status()).isEqualTo(ProjectionStatus.DEGRADED);
    assertThat(stored.lastErrorCode()).isEqualTo("CURRENT");
  }

  @Test
  void schemaRejectsInvalidTechnicalStates() {
    Instant now = Instant.now();
    var operation = pendingOperation(UUID.randomUUID(), now);
    operations.insert(operation);

    var wrongExchange = new OutboxMessage(UUID.randomUUID(), "COMMAND", "TestCommand", 1,
        "wrong.exchange", "users", operation.operationId(), operation.correlationId(), null,
        UUID.randomUUID(), 1, operation.idempotencyKey(), "{}", "{}", OutboxStatus.PENDING,
        null, null, 0, null, null, null, now, now);
    assertThatThrownBy(() -> outbox.insert(wrongExchange)).isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(() -> jdbc.sql("""
        INSERT INTO account_session_revocation_cutoffs (
          account_id, revoked_before_or_at, cutoff_kind, except_session_id,
          reason_code, source_message_id, updated_at
        ) VALUES (:accountId, :cutoff, 'ALL', :exceptSessionId, 'TEST', :messageId, :updatedAt)
        """)
        .param("accountId", UUID.randomUUID())
        .param("cutoff", now.getEpochSecond())
        .param("exceptSessionId", UUID.randomUUID())
        .param("messageId", UUID.randomUUID())
        .param("updatedAt", now)
        .update()).isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(() -> jdbc.sql("""
        INSERT INTO projection_recovery_jobs (
          recovery_job_id, projection_name, mode, entity_id, generation,
          owner_logical_component_id, query_type, operation_id, cursor_value,
          status, started_at, updated_at, completed_at, last_error_code
        ) VALUES (:jobId, 'authorization', 'FULL', :entityId, 1,
          'authorization', 'ListAccountAuthorizationSnapshotsPage', NULL, NULL,
          'PENDING', :startedAt, :updatedAt, NULL, NULL)
        """)
        .param("jobId", UUID.randomUUID())
        .param("entityId", UUID.randomUUID())
        .param("startedAt", now)
        .param("updatedAt", now)
        .update()).isInstanceOf(DataIntegrityViolationException.class);
  }

  private static ClientOperation pendingOperation(UUID operationId, Instant now) {
    return pendingOperation(operationId, UUID.randomUUID(), "idem-" + operationId, now);
  }

  private static ClientOperation pendingOperation(UUID operationId, UUID actorAccountId,
      String idempotencyKey, Instant now) {
    return new ClientOperation(
        operationId,
        actorAccountId,
        "test.operation",
        "TestCommand",
        "users",
        "users",
        UUID.randomUUID(),
        idempotencyKey,
        OperationStatus.PENDING,
        null,
        null,
        null,
        now,
        now,
        null,
        null);
  }

  private static OutboxMessage pendingOutbox(ClientOperation operation, UUID messageId, Instant now) {
    return new OutboxMessage(
        messageId,
        "COMMAND",
        "TestCommand",
        1,
        "platform.commands",
        "users",
        operation.operationId(),
        operation.correlationId(),
        null,
        UUID.randomUUID(),
        7,
        operation.idempotencyKey(),
        "{}",
        "{\"test\":true}",
        OutboxStatus.PENDING,
        null,
        null,
        0,
        null,
        null,
        null,
        now,
        now);
  }

  private static InboxMessage inboxMessage(String consumerName, UUID messageId, Instant now) {
    return new InboxMessage(
        consumerName,
        messageId,
        "EVENT",
        "TestEvent",
        1,
        "test-owner",
        UUID.randomUUID(),
        null,
        null,
        UUID.randomUUID(),
        1,
        now,
        now);
  }
}
