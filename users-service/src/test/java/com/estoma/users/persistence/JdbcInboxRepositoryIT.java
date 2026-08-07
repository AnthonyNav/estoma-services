package com.estoma.users.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the Consumer Inbox primitive: {@code (consumerName, messageId)}
 * is the whole idempotency mechanism (docs/system-contract.md §7, §13).
 */
class JdbcInboxRepositoryIT extends AbstractPostgresIT {

  @Autowired private JdbcInboxRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  void firstInsertForAMessageIdReturnsTrue() {
    boolean inserted = repository.tryInsert("users-service.commands", UUID.randomUUID(), UUID.randomUUID());

    assertThat(inserted).isTrue();
  }

  @Test
  void repeatedInsertForTheSameConsumerAndMessageIdReturnsFalse() {
    UUID messageId = UUID.randomUUID();
    repository.tryInsert("users-service.commands", messageId, UUID.randomUUID());

    boolean secondAttempt = repository.tryInsert("users-service.commands", messageId, UUID.randomUUID());

    assertThat(secondAttempt).isFalse();
  }

  @Test
  void sameMessageIdUnderDifferentConsumerNamesIsNotADuplicate() {
    UUID messageId = UUID.randomUUID();

    boolean first = repository.tryInsert("users-service.commands", messageId, UUID.randomUUID());
    boolean second = repository.tryInsert("users-service.other-consumer", messageId, UUID.randomUUID());

    assertThat(first).isTrue();
    assertThat(second).isTrue();
  }

  @Test
  void exactlyOneOfTwoConcurrentInsertsForTheSameMessageIdSucceeds() throws Exception {
    UUID messageId = UUID.randomUUID();
    Callable<Boolean> attempt = () -> repository.tryInsert("users-service.commands", messageId, UUID.randomUUID());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> futures = executor.invokeAll(List.of(attempt, attempt));
      AtomicInteger successCount = new AtomicInteger();
      for (Future<Boolean> future : futures) {
        if (future.get()) {
          successCount.incrementAndGet();
        }
      }

      assertThat(successCount.get()).isEqualTo(1);
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void purgeProcessedOlderThanRemovesOnlyRecordsPastTheThreshold() {
    // The Postgres container is shared across every *IT class in this JVM
    // (AbstractPostgresIT), so processed_messages already holds rows from
    // other tests — assert on THIS test's own row via a targeted lookup
    // rather than on the table's total row count.
    UUID consumerName = UUID.randomUUID(); // reused as a unique, greppable consumer_name value
    UUID messageId = UUID.randomUUID();
    repository.tryInsert(consumerName.toString(), messageId, UUID.randomUUID());
    assertThat(existsInInbox(consumerName.toString(), messageId)).isTrue();

    // Threshold in the past: this test's freshly-inserted row must survive.
    repository.purgeProcessedOlderThan(Instant.now().minus(1, ChronoUnit.DAYS));
    assertThat(existsInInbox(consumerName.toString(), messageId)).isTrue();

    // Threshold in the future: every row processed "before" it, including
    // this one, is purged.
    repository.purgeProcessedOlderThan(Instant.now().plusSeconds(1));
    assertThat(existsInInbox(consumerName.toString(), messageId)).isFalse();
  }

  private boolean existsInInbox(String consumerName, UUID messageId) {
    return jdbc.sql(
            "SELECT count(*) FROM processed_messages WHERE consumer_name = :consumerName AND message_id = :messageId")
        .param("consumerName", consumerName)
        .param("messageId", messageId)
        .query(Long.class)
        .single()
        > 0;
  }
}
