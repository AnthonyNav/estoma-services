package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcOutboxRepositoryIT extends AbstractPostgresIT {

  @Autowired private JdbcOutboxRepository repository;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactionTemplate;

  @BeforeEach
  void clearOutbox() {
    jdbc.sql("DELETE FROM outbox_messages").update();
  }

  @Test
  void appendInsertsAPendingRow() {
    UUID messageId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID causationId = UUID.randomUUID();
    Actor actor = new Actor("ACCOUNT", aggregateId, null, "SELF", null);

    repository.append(
        new OutboxAppendRequest(
            messageId, "AccountCreated", 1, MessageKind.EVENT.name(), aggregateId, 1, correlationId, causationId,
            operationId, "request-123", actor, "{}", Instant.now()));

    String status = jdbc.sql("SELECT status FROM outbox_messages WHERE message_id = :id").param("id", messageId).query(String.class).single();
    assertThat(status).isEqualTo("PENDING");

    OutboxRecord record = repository.claimBatch(100).stream().filter(candidate -> candidate.messageId().equals(messageId)).findFirst().orElseThrow();
    assertThat(record.operationId()).isEqualTo(operationId);
    assertThat(record.idempotencyKey()).isEqualTo("request-123");
    assertThat(record.actor()).isEqualTo(actor);
    assertThat(record.correlationId()).isEqualTo(correlationId);
    assertThat(record.causationId()).isEqualTo(causationId);
  }

  @Test
  void concurrentRelaysDoNotClaimTheSamePendingRow() throws Exception {
    UUID messageId = UUID.randomUUID();
    repository.append(
        new OutboxAppendRequest(
            messageId, "AccountCreated", 1, MessageKind.EVENT.name(), UUID.randomUUID(), 1, UUID.randomUUID(), null,
            null, null, null, "{}", Instant.now()));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch claimed = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<List<OutboxRecord>> first = executor.submit(() -> claimWhileHoldingTransaction(ready, start, claimed, release));
      Future<List<OutboxRecord>> second = executor.submit(() -> claimWhileHoldingTransaction(ready, start, claimed, release));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
      release.countDown();

      List<OutboxRecord> firstClaim = first.get(5, TimeUnit.SECONDS);
      List<OutboxRecord> secondClaim = second.get(5, TimeUnit.SECONDS);
      assertThat(firstClaim).hasSizeBetween(0, 1);
      assertThat(secondClaim).hasSizeBetween(0, 1);
      assertThat(firstClaim.size() + secondClaim.size()).isEqualTo(1);
    }
  }

  private List<OutboxRecord> claimWhileHoldingTransaction(
      CountDownLatch ready, CountDownLatch start, CountDownLatch claimed, CountDownLatch release) {
    return transactionTemplate.execute(status -> {
      try {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent claim did not start");
        List<OutboxRecord> rows = repository.claimBatch(1);
        claimed.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent claim was not released");
        return rows;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    });
  }
}
