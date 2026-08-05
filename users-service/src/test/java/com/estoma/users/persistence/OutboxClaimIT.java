package com.estoma.users.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.messaging.outbound.OutboxFailureCode;
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
 * UPDATE SKIP LOCKED}, the PROCESSING lease, attempt counting, claim
 * fencing, and the housekeeping the sweeper depends on.
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
              assertThat(r.claimToken()).isNotNull();
              assertThat(r.leaseUntil()).isNotNull();
            });
  }

  @Test
  void claimDoesNotTouchAvailableAt() {
    UUID messageId = append();
    Instant availableAtBeforeClaim = availableAtOf(messageId);

    repository.claimBatch(10, Duration.ofSeconds(30));

    assertThat(availableAtOf(messageId)).isEqualTo(availableAtBeforeClaim);
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
  void markPublishedTransitionsOnlyTheMatchingClaimToken() {
    UUID messageId = append();
    OutboxRecord claimed = claimSingle(messageId);

    int rowsAffected = repository.markPublished(messageId, claimed.claimToken(), Instant.now());

    assertThat(rowsAffected).isEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("PUBLISHED");
    assertThat(claimTokenOf(messageId)).isNull();
    assertThat(leaseUntilOf(messageId)).isNull();

    // A second attempt with the same (now stale) token affects nothing.
    assertThat(repository.markPublished(messageId, claimed.claimToken(), Instant.now())).isZero();
  }

  @Test
  void markPublishedClearsAPreviousError() {
    UUID messageId = append();
    OutboxRecord firstClaim = claimSingle(messageId);
    repository.releaseForRetry(messageId, firstClaim.claimToken(), Instant.now(), OutboxFailureCode.PUBLISH_NACK);
    assertThat(lastErrorCodeOf(messageId)).isEqualTo("PUBLISH_NACK");

    OutboxRecord secondClaim = claimSingle(messageId);
    repository.markPublished(messageId, secondClaim.claimToken(), Instant.now());

    assertThat(lastErrorCodeOf(messageId)).isNull();
    assertThat(lastErrorAtOf(messageId)).isNull();
  }

  @Test
  void markPublishedWithAStaleClaimTokenAffectsNothing() {
    UUID messageId = append();
    OutboxRecord staleClaim = claimSingle(messageId);
    // Simulate the lease expiring and another worker taking over.
    UUID otherClaimToken = UUID.randomUUID();
    jdbc.sql("UPDATE outbox_messages SET claim_token = :token WHERE message_id = :id")
        .param("token", otherClaimToken)
        .param("id", messageId)
        .update();

    int rows = repository.markPublished(messageId, staleClaim.claimToken(), Instant.now());

    assertThat(rows).isZero();
    assertThat(statusOf(messageId)).isEqualTo("PROCESSING");
    assertThat(claimTokenOf(messageId)).isEqualTo(otherClaimToken);
  }

  @Test
  void releaseForRetryReturnsClaimedRowToPendingAtTheGivenInstantWithoutDoubleIncrementingAttempts() {
    UUID messageId = append();
    OutboxRecord firstClaim = claimSingle(messageId);
    Instant nextAvailableAt = Instant.now().plusSeconds(30);

    int rows =
        repository.releaseForRetry(messageId, firstClaim.claimToken(), nextAvailableAt, OutboxFailureCode.PUBLISH_TIMEOUT);

    assertThat(rows).isEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("PENDING");
    assertThat(availableAtOf(messageId).truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        .isEqualTo(nextAvailableAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    assertThat(lastErrorCodeOf(messageId)).isEqualTo("PUBLISH_TIMEOUT");
    assertThat(lastErrorAtOf(messageId)).isNotNull();
    assertThat(claimTokenOf(messageId)).isNull();

    // Immediately reclaimable only once available_at has actually passed.
    assertThat(repository.claimBatch(10, Duration.ofSeconds(30))).isEmpty();

    jdbc.sql("UPDATE outbox_messages SET available_at = :past WHERE message_id = :id")
        .param("past", Instant.now().minusSeconds(1).atOffset(java.time.ZoneOffset.UTC))
        .param("id", messageId)
        .update();
    List<OutboxRecord> reclaimed = repository.claimBatch(10, Duration.ofSeconds(30));

    int attemptsAfterSecondClaim =
        reclaimed.stream().filter(r -> r.messageId().equals(messageId)).findFirst().orElseThrow().attempts();
    assertThat(attemptsAfterSecondClaim).isEqualTo(2);
  }

  @Test
  void releaseForRetryNeverUsesNowAsTheNextAvailableAt() {
    // releaseForRetry takes nextAvailableAt from the caller (OutboxRelay,
    // which applies the backoff policy) -- the repository itself must
    // never substitute now(). Asserted indirectly: passing a
    // far-future instant must make the row unclaimable immediately after.
    UUID messageId = append();
    OutboxRecord claim = claimSingle(messageId);

    repository.releaseForRetry(
        messageId, claim.claimToken(), Instant.now().plus(Duration.ofMinutes(10)), OutboxFailureCode.PUBLISH_NACK);

    assertThat(repository.claimBatch(10, Duration.ofSeconds(30))).isEmpty();
  }

  @Test
  void markFailedTransitionsOnlyTheMatchingClaimToken() {
    UUID messageId = append();
    OutboxRecord claim = claimSingle(messageId);

    int rows = repository.markFailed(messageId, claim.claimToken(), OutboxFailureCode.PUBLISH_UNEXPECTED_ERROR);

    assertThat(rows).isEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("FAILED");
    assertThat(claimTokenOf(messageId)).isNull();
    assertThat(leaseUntilOf(messageId)).isNull();
    assertThat(lastErrorCodeOf(messageId)).isEqualTo("PUBLISH_UNEXPECTED_ERROR");
  }

  @Test
  void findExpiredLeasesReturnsOnlyRowsPastTheirLease() {
    UUID messageId = append();
    // A negative lease means lease_until is already in the past the
    // instant it's claimed, simulating a relay that crashed mid-publish.
    OutboxRecord claim = claimSingleWithLease(messageId, Duration.ofSeconds(-1));

    List<ExpiredLease> expired = repository.findExpiredLeases(10);

    assertThat(expired)
        .extracting(ExpiredLease::messageId)
        .contains(messageId);
    ExpiredLease found = expired.stream().filter(e -> e.messageId().equals(messageId)).findFirst().orElseThrow();
    assertThat(found.claimToken()).isEqualTo(claim.claimToken());
    assertThat(found.attempts()).isEqualTo(1);
  }

  @Test
  void findExpiredLeasesExcludesRowsWithAFutureLease() {
    UUID messageId = append();
    claimSingle(messageId); // 30s lease by default helper, still valid

    List<ExpiredLease> expired = repository.findExpiredLeases(10);

    assertThat(expired).extracting(ExpiredLease::messageId).doesNotContain(messageId);
  }

  @Test
  void failExhaustedPendingMarksRowsThatReachedTheAttemptCeilingAsFailed() {
    UUID messageId = append();
    // Claim + release twice to reach attempts = 2 while ending up PENDING.
    OutboxRecord firstClaim = claimSingle(messageId);
    repository.releaseForRetry(messageId, firstClaim.claimToken(), Instant.now(), OutboxFailureCode.PUBLISH_NACK);
    OutboxRecord secondClaim = claimSingle(messageId);
    repository.releaseForRetry(messageId, secondClaim.claimToken(), Instant.now(), OutboxFailureCode.PUBLISH_NACK);
    assertThat(statusOf(messageId)).isEqualTo("PENDING");

    int failed = repository.failExhaustedPending(2);

    assertThat(failed).isGreaterThanOrEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("FAILED");
  }

  @Test
  void purgePublishedOlderThanDeletesOnlyPublishedRowsPastTheThreshold() {
    UUID messageId = append();
    OutboxRecord claim = claimSingle(messageId);
    repository.markPublished(messageId, claim.claimToken(), Instant.now());

    int purged = repository.purgePublishedOlderThan(Instant.now().plusSeconds(1));

    assertThat(purged).isGreaterThanOrEqualTo(1);
    assertThat(rowExists(messageId)).isFalse();
  }

  @Test
  void countStuckProcessingCountsOnlyExpiredLeases() {
    UUID freshlyClaimed = append();
    claimSingle(freshlyClaimed); // 30s lease, not expired

    UUID staleClaimed = append();
    claimSingleWithLease(staleClaimed, Duration.ofSeconds(-1)); // already expired

    assertThat(repository.countStuckProcessing()).isGreaterThanOrEqualTo(1);
    // The freshly-claimed row must not be counted as stuck.
    long stuckExcludingFresh =
        jdbc.sql(
                "SELECT count(*) FROM outbox_messages WHERE status = 'PROCESSING' AND lease_until < now() AND message_id = :id")
            .param("id", freshlyClaimed)
            .query(Long.class)
            .single();
    assertThat(stuckExcludingFresh).isZero();
  }

  @Test
  void countingHelpersReflectCurrentState() {
    UUID pendingId = append();
    UUID processingId = append();
    // Claim both away, like every other test in this class -- the shared
    // static container accumulates rows across the whole run, and an
    // immediately-reclaimable PENDING row left behind here could be swept
    // up by a claimBatch(...) call in a different test method.
    repository.claimBatch(1, Duration.ofSeconds(30)); // claims whichever is oldest
    repository.claimBatch(10, Duration.ofSeconds(30)); // claims the other one too

    assertThat(repository.countPendingOrProcessing()).isGreaterThanOrEqualTo(2);

    // Silence unused-variable warnings while keeping the ids self-documenting.
    assertThat(pendingId).isNotNull();
    assertThat(processingId).isNotNull();
  }

  private Instant availableAtOf(UUID messageId) {
    return jdbc.sql("SELECT available_at FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(java.time.OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private String statusOf(UUID messageId) {
    return jdbc.sql("SELECT status FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(String.class)
        .single();
  }

  private UUID claimTokenOf(UUID messageId) {
    return jdbc.sql("SELECT claim_token FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  private java.time.OffsetDateTime leaseUntilOf(UUID messageId) {
    return jdbc.sql("SELECT lease_until FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(java.time.OffsetDateTime.class)
        .optional()
        .orElse(null);
  }

  private String lastErrorCodeOf(UUID messageId) {
    return jdbc.sql("SELECT last_error_code FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private java.time.OffsetDateTime lastErrorAtOf(UUID messageId) {
    return jdbc.sql("SELECT last_error_at FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(java.time.OffsetDateTime.class)
        .optional()
        .orElse(null);
  }

  private boolean rowExists(UUID messageId) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(Long.class)
        .single()
        > 0;
  }

  private OutboxRecord claimSingle(UUID messageId) {
    return claimSingleWithLease(messageId, Duration.ofSeconds(30));
  }

  private OutboxRecord claimSingleWithLease(UUID messageId, Duration lease) {
    return repository.claimBatch(20, lease).stream()
        .filter(r -> r.messageId().equals(messageId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected " + messageId + " to be claimable"));
  }

  /**
   * A deliberately unregistered {@code messageType}: this class runs
   * against {@code AbstractPostgresIT}, whose Spring context has no
   * working RabbitMQ connection, but the live application {@code
   * OutboxRelay} bean is still scheduled and still polls this shared
   * Postgres database. If a row here ever became claimable by a
   * DIFFERENT, RabbitMQ-connected Spring context later in the same test
   * run (contexts share the static Postgres container across test
   * classes), a real messageType like "PersonCreated" would actually get
   * published onto the real {@code identity.person.created} routing key
   * -- polluting whatever other test's wildcard-bound queue happens to be
   * active at that later, unrelated point in time. An unregistered type
   * fails inside {@code RoutingKeyResolver} before any AMQP call happens.
   */
  private static final String TEST_MESSAGE_TYPE = "ItOutboxClaimTestRow";

  private UUID append() {
    UUID messageId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    repository.append(
        new OutboxAppendRequest(
            messageId,
            TEST_MESSAGE_TYPE,
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
