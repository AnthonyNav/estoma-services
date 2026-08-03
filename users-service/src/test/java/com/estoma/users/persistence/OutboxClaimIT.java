package com.estoma.users.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the Transactional Outbox relay-facing claim/mark/release/purge
 * API (docs/system-contract.md §8): multi-replica claiming via {@code FOR
 * UPDATE SKIP LOCKED}, the PROCESSING lease, attempt counting, and the
 * housekeeping the sweeper depends on.
 */
class OutboxClaimIT extends AbstractPostgresIT {

  @Autowired private JdbcOutboxRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  void appendedRowIsImmediatelyClaimable() {
    UUID messageId = append();

    List<OutboxRecord> claimed = repository.claimBatch(10, Duration.ofSeconds(30));

    assertThat(claimed).extracting(OutboxRecord::messageId).contains(messageId);
    assertThat(claimed)
        .filteredOn(r -> r.messageId().equals(messageId))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo(OutboxStatus.PROCESSING);
              assertThat(r.attempts()).isEqualTo(1);
            });
  }

  @Test
  void claimedRowIsNotReturnedByASecondClaimBeforeItsLeaseExpires() {
    append();

    List<OutboxRecord> firstClaim = repository.claimBatch(10, Duration.ofSeconds(30));
    List<OutboxRecord> secondClaim = repository.claimBatch(10, Duration.ofSeconds(30));

    assertThat(firstClaim).isNotEmpty();
    assertThat(secondClaim).isEmpty();
  }

  @Test
  void twoConcurrentClaimersReceiveDisjointSets() throws Exception {
    for (int i = 0; i < 20; i++) {
      append();
    }

    Callable<List<UUID>> claim =
        () ->
            repository.claimBatch(10, Duration.ofSeconds(30)).stream()
                .map(OutboxRecord::messageId)
                .collect(Collectors.toList());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<List<UUID>>> futures = executor.invokeAll(List.of(claim, claim));
      Set<UUID> firstBatch = Set.copyOf(futures.get(0).get());
      Set<UUID> secondBatch = Set.copyOf(futures.get(1).get());

      assertThat(firstBatch).doesNotContainAnyElementsOf(secondBatch);
      assertThat(firstBatch.size() + secondBatch.size()).isEqualTo(20);
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void markPublishedTransitionsOnlyProcessingRows() {
    UUID messageId = append();
    List<OutboxRecord> claimed = repository.claimBatch(10, Duration.ofSeconds(30));
    assertThat(claimed).extracting(OutboxRecord::messageId).contains(messageId);

    int rowsAffected = repository.markPublished(List.of(messageId));

    assertThat(rowsAffected).isEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("PUBLISHED");

    // A second attempt to mark the same (now PUBLISHED) row affects nothing.
    assertThat(repository.markPublished(List.of(messageId))).isZero();
  }

  @Test
  void releaseForRetryReturnsClaimedRowToPendingWithoutDoubleIncrementingAttempts() {
    UUID messageId = append();
    List<OutboxRecord> claimed = repository.claimBatch(10, Duration.ofSeconds(30));
    int attemptsAfterFirstClaim =
        claimed.stream().filter(r -> r.messageId().equals(messageId)).findFirst().orElseThrow().attempts();

    repository.releaseForRetry(List.of(messageId));
    assertThat(statusOf(messageId)).isEqualTo("PENDING");

    List<OutboxRecord> reclaimed = repository.claimBatch(10, Duration.ofSeconds(30));
    int attemptsAfterSecondClaim =
        reclaimed.stream().filter(r -> r.messageId().equals(messageId)).findFirst().orElseThrow().attempts();

    assertThat(attemptsAfterFirstClaim).isEqualTo(1);
    assertThat(attemptsAfterSecondClaim).isEqualTo(2);
  }

  @Test
  void releaseExpiredLeasesReturnsStaleProcessingRowsToPending() {
    UUID messageId = append();
    // A negative lease means available_at is already in the past the
    // instant it's claimed, simulating a relay that crashed mid-publish.
    repository.claimBatch(10, Duration.ofSeconds(-1));
    assertThat(statusOf(messageId)).isEqualTo("PROCESSING");

    int released = repository.releaseExpiredLeases();

    assertThat(released).isGreaterThanOrEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("PENDING");
  }

  @Test
  void failExhaustedPendingMarksRowsThatReachedTheAttemptCeilingAsFailed() {
    UUID messageId = append();
    // Claim + release twice to reach attempts = 2 while ending up PENDING.
    repository.claimBatch(10, Duration.ofSeconds(30));
    repository.releaseForRetry(List.of(messageId));
    repository.claimBatch(10, Duration.ofSeconds(30));
    repository.releaseForRetry(List.of(messageId));
    assertThat(statusOf(messageId)).isEqualTo("PENDING");

    int failed = repository.failExhaustedPending(2);

    assertThat(failed).isGreaterThanOrEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("FAILED");
  }

  @Test
  void purgePublishedOlderThanDeletesOnlyPublishedRowsPastTheThreshold() {
    UUID messageId = append();
    repository.claimBatch(10, Duration.ofSeconds(30));
    repository.markPublished(List.of(messageId));

    int purged = repository.purgePublishedOlderThan(Instant.now().plusSeconds(1));

    assertThat(purged).isGreaterThanOrEqualTo(1);
    assertThat(rowExists(messageId)).isFalse();
  }

  @Test
  void countingHelpersReflectCurrentState() {
    UUID pendingId = append();
    UUID processingId = append();
    repository.claimBatch(1, Duration.ofSeconds(30)); // claims whichever is oldest; make it deterministic below

    // Claim exactly the second message too, so both are accounted for
    // regardless of which one the single-row claim above picked up.
    repository.claimBatch(10, Duration.ofSeconds(30));

    assertThat(repository.countPendingOrProcessing()).isGreaterThanOrEqualTo(2);
    assertThat(repository.countProcessing()).isGreaterThanOrEqualTo(2);
    assertThat(repository.oldestPendingOccurredAt()).isNull();

    // Silence unused-variable warnings while keeping the ids self-documenting.
    assertThat(pendingId).isNotNull();
    assertThat(processingId).isNotNull();
  }

  private String statusOf(UUID messageId) {
    return jdbc.sql("SELECT status FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(String.class)
        .single();
  }

  private boolean rowExists(UUID messageId) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(Long.class)
        .single()
        > 0;
  }

  private UUID append() {
    UUID messageId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    repository.append(
        new OutboxAppendRequest(
            messageId,
            "PersonCreated",
            1,
            "EVENT",
            personId,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "{\"personId\":\"" + personId + "\"}",
            null,
            Instant.now()));
    return messageId;
  }
}
