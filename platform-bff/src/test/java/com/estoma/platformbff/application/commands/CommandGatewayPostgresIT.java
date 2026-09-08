package com.estoma.platformbff.application.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.operations.ResultApplicationService;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.CommandSubmission;
import com.estoma.platformbff.contracts.messaging.CommandSubmissionResult;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.infrastructure.persistence.JdbcClientOperationRepository;
import com.estoma.platformbff.infrastructure.persistence.JdbcOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "platform.outbox.enabled=false",
    "platform.messaging.listeners-enabled=false",
    "platform.messaging.topology-declare=false"
})
@Tag("integration")
class CommandGatewayPostgresIT {
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

  @Autowired CommandGatewayService gateway;
  @Autowired ResultApplicationService results;
  @Autowired JdbcClientOperationRepository operations;
  @Autowired JdbcOutboxRepository outbox;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clear() {
    jdbc.sql("""
        TRUNCATE TABLE quarantined_messages, inbox_messages, outbox_messages,
          idempotency_records, client_operations CASCADE
        """).update();
  }

  @Test
  void retryPreservesTheSubmittedTimestampAfterDatabaseRoundTrip() {
    var command = submission(UUID.randomUUID(), "stable-receipt-time", "Ada");
    var first = gateway.submit(command);
    var retry = gateway.submit(command);

    assertThat(retry.operationId()).isEqualTo(first.operationId());
    assertThat(retry.pollPath()).isEqualTo(first.pollPath());
    assertThat(retry.submittedAt()).isEqualTo(first.submittedAt());
    assertThat(operations.findById(first.operationId()).orElseThrow().createdAt())
        .isEqualTo(first.submittedAt());
  }

  @Test
  void pendingOperationHasNoIdempotencyExpiryUntilTerminalResult() {
    UUID accountId = UUID.randomUUID();
    var submitted = gateway.submit(submission(accountId, "ttl-key", "Ada"));

    Instant beforeTerminal = jdbc.sql("""
        SELECT expires_at FROM idempotency_records WHERE operation_id = :operationId
        """)
        .param("operationId", submitted.operationId())
        .query((rs, rowNum) -> {
          var value = rs.getTimestamp("expires_at");
          return value == null ? null : value.toInstant();
        })
        .optional().orElse(null);
    assertThat(beforeTerminal).isNull();

    var operation = operations.findById(submitted.operationId()).orElseThrow();
    UUID commandMessageId = outbox.findMessageIdByOperationId(submitted.operationId()).orElseThrow();
    Instant appliedAt = Instant.now();
    var result = new IncomingResult(
        UUID.randomUUID(), "CreatePersonSucceeded", 1, MessageKind.RESULT, "users",
        appliedAt, operation.correlationId(), commandMessageId, submitted.operationId(),
        UUID.randomUUID(), 1, "SUCCEEDED", "{\"personId\":\"ok\"}", null, null);

    assertThat(results.apply(result)).isTrue();

    Instant expiresAt = jdbc.sql("""
        SELECT expires_at FROM idempotency_records WHERE operation_id = :operationId
        """)
        .param("operationId", submitted.operationId())
        .query((rs, rowNum) -> rs.getTimestamp("expires_at").toInstant())
        .single();
    var terminal = operations.findById(submitted.operationId()).orElseThrow();
    assertThat(terminal.status()).isEqualTo(com.estoma.platformbff.domain.operation.OperationStatus.SUCCEEDED);
    assertThat(expiresAt).isAfter(terminal.completedAt().plus(23, ChronoUnit.HOURS));
    assertThat(expiresAt).isBefore(terminal.completedAt().plus(25, ChronoUnit.HOURS));
  }

  @Test
  void concurrentSameKeyAndFingerprintProducesOneOperationAndOutbox() throws Exception {
    UUID accountId = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<CommandSubmissionResult> call = () -> {
        if (!start.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("concurrent submit did not start");
        }
        return gateway.submit(submission(accountId, "same-key", "Ada"));
      };
      var first = pool.submit(call);
      var second = pool.submit(call);
      start.countDown();

      var left = first.get(10, TimeUnit.SECONDS);
      var right = second.get(10, TimeUnit.SECONDS);
      assertThat(left.operationId()).isEqualTo(right.operationId());
      assertThat(count("client_operations")).isEqualTo(1);
      assertThat(count("idempotency_records")).isEqualTo(1);
      assertThat(count("outbox_messages")).isEqualTo(1);
    }
  }

  @Test
  void concurrentSameKeyDifferentFingerprintReturnsDeterministicConflict() throws Exception {
    UUID accountId = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Object> ada = () -> concurrentSubmit(start, submission(accountId, "conflict-key", "Ada"));
      Callable<Object> grace = () -> concurrentSubmit(start, submission(accountId, "conflict-key", "Grace"));
      var first = pool.submit(ada);
      var second = pool.submit(grace);
      start.countDown();

      Object left = first.get(10, TimeUnit.SECONDS);
      Object right = second.get(10, TimeUnit.SECONDS);
      assertThat(java.util.List.of(left, right).stream()
          .filter(CommandSubmissionResult.class::isInstance).count()).isEqualTo(1);
      assertThat(java.util.List.of(left, right).stream()
          .filter(CommandGatewayService.IdempotencyConflictException.class::isInstance).count()).isEqualTo(1);
      assertThat(count("client_operations")).isEqualTo(1);
      assertThat(count("idempotency_records")).isEqualTo(1);
      assertThat(count("outbox_messages")).isEqualTo(1);
    }
  }

  @Test
  void unclassifiedEntryNormalizationIsIdempotentAndDistinctFromLegacyFalseChecks() {
    UUID accountId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    String intent = "{\"washExecutionId\":\"" + executionId + "\",\"decision\":\"REJECTED\","
        + "\"rejectionReason\":\"Motivo operativo\",\"expectedVersion\":1}";
    String ownerAtFirstAttempt = intent.substring(0, intent.length() - 1)
        + ",\"decidedByAccountId\":\"" + accountId
        + "\",\"decidedAt\":\"2026-09-06T12:00:00Z\"}";
    String ownerAtRetry = intent.substring(0, intent.length() - 1)
        + ",\"decidedByAccountId\":\"" + accountId
        + "\",\"decidedAt\":\"2026-09-06T12:01:00Z\"}";

    var first = gateway.submit(entryDecisionSubmission(
        accountId, executionId, "unclassified-key", ownerAtFirstAttempt, intent));
    var retry = gateway.submit(entryDecisionSubmission(
        accountId, executionId, "unclassified-key", ownerAtRetry, intent));

    assertThat(retry.operationId()).isEqualTo(first.operationId());
    assertThat(retry.reused()).isTrue();
    assertThat(count("client_operations")).isEqualTo(1);
    assertThat(count("idempotency_records")).isEqualTo(1);
    assertThat(count("outbox_messages")).isEqualTo(1);
    Map<String, Object> wire = jdbc.sql("""
        SELECT message_type, message_version, payload FROM outbox_messages
        WHERE operation_id = :operationId
        """).param("operationId", first.operationId()).query().singleRow();
    assertThat(wire.get("message_type")).isEqualTo("DecideWashEntry");
    assertThat(wire.get("message_version")).isEqualTo(1);
    assertThat(wire.get("payload").toString())
        .doesNotContain("identityConfirmed", "requirementsSatisfied", ": null");

    String legacyIntent = intent.substring(0, intent.length() - 1)
        + ",\"identityConfirmed\":false,\"requirementsSatisfied\":false}";
    assertThatThrownBy(() -> gateway.submit(entryDecisionSubmission(
        accountId, executionId, "unclassified-key", legacyIntent, legacyIntent)))
        .isInstanceOf(CommandGatewayService.IdempotencyConflictException.class);
    assertThat(count("client_operations")).isEqualTo(1);
    assertThat(count("outbox_messages")).isEqualTo(1);
  }

  @Test
  void contradictoryLateResultDoesNotEnterInbox() {
    UUID accountId = UUID.randomUUID();
    var submitted = gateway.submit(submission(accountId, "terminal-key", "Ada"));
    var operation = operations.findById(submitted.operationId()).orElseThrow();
    UUID commandMessageId = outbox.findMessageIdByOperationId(submitted.operationId()).orElseThrow();

    var succeeded = new IncomingResult(
        UUID.randomUUID(), "CreatePersonSucceeded", 1, MessageKind.RESULT, "users",
        Instant.now(), operation.correlationId(), commandMessageId, submitted.operationId(),
        UUID.randomUUID(), 1, "SUCCEEDED", "{\"personId\":\"ok\"}", null, null);
    assertThat(results.apply(succeeded)).isTrue();

    var rejected = new IncomingResult(
        UUID.randomUUID(), "CreatePersonRejected", 1, MessageKind.RESULT, "users",
        Instant.now(), operation.correlationId(), commandMessageId, submitted.operationId(),
        null, null, "REJECTED", null, "CONTRADICTORY", "{\"code\":\"CONTRADICTORY\"}");
    assertThat(results.apply(rejected)).isFalse();
    assertThat(count("inbox_messages")).isEqualTo(1);
  }

  private Object concurrentSubmit(CountDownLatch start, CommandSubmission submission) throws Exception {
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent submit did not start");
    }
    try {
      return gateway.submit(submission);
    } catch (CommandGatewayService.IdempotencyConflictException conflict) {
      return conflict;
    }
  }

  private CommandSubmission submission(UUID accountId, String key, String firstName) {
    UUID aggregateId = UUID.nameUUIDFromBytes(
        (accountId + ":" + key).getBytes(StandardCharsets.UTF_8));
    return new CommandSubmission(
        "create-person",
        "CreatePerson",
        "{\"firstName\":\"" + firstName + "\"}",
        key,
        accountId,
        ActorContext.account(accountId, "GLOBAL", null),
        aggregateId,
        null);
  }

  private CommandSubmission entryDecisionSubmission(UUID accountId, UUID executionId, String key,
      String ownerPayload, String intentPayload) {
    return new CommandSubmission(
        "wash-decide-entry", "DecideWashEntry", ownerPayload, intentPayload, key, accountId,
        ActorContext.account(accountId, "GLOBAL", null), executionId, 1, executionId, 1);
  }

  private int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }
}
