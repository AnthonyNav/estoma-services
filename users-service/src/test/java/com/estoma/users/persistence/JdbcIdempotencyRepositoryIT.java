package com.estoma.users.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.IdempotencyRecord;
import com.estoma.users.application.IdempotencyStatus;
import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies {@link JdbcIdempotencyRepository} against a real database, in
 * particular the property {@link PersonCommandDispatcher} relies on: two
 * concurrent claims for the same {@code (producer, idempotencyKey)} can
 * never both be told they are the new claim (commands.md §9).
 */
class JdbcIdempotencyRepositoryIT extends AbstractPostgresIT {

  @Autowired private JdbcIdempotencyRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void firstClaimForAKeyIsTheNewClaim() {
    String key = UUID.randomUUID().toString();
    UUID personId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();

    IdempotencyRecord claim =
        repository.claim("bff", key, "CreatePerson", personId, "fingerprint-a", messageId, Instant.now());

    assertThat(claim.isNewClaim(messageId)).isTrue();
    assertThat(claim.terminalStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    assertThat(claim.aggregateVersion()).isNull();
    assertThat(claim.errorCode()).isNull();
  }

  @Test
  void aSecondClaimForTheSameKeyIsNotTheNewClaimAndSeesTheMarkedOutcome() {
    String key = UUID.randomUUID().toString();
    UUID personId = UUID.randomUUID();
    UUID firstMessageId = UUID.randomUUID();
    UUID secondMessageId = UUID.randomUUID();

    repository.claim("bff", key, "CreatePerson", personId, "fingerprint-a", firstMessageId, Instant.now());
    repository.markSucceeded("bff", key, 3, Instant.now());

    IdempotencyRecord claim =
        repository.claim("bff", key, "CreatePerson", personId, "fingerprint-a", secondMessageId, Instant.now());

    assertThat(claim.isNewClaim(secondMessageId)).isFalse();
    assertThat(claim.claimedByMessageId()).isEqualTo(firstMessageId);
    assertThat(claim.terminalStatus()).isEqualTo(IdempotencyStatus.SUCCEEDED);
    assertThat(claim.aggregateVersion()).isEqualTo(3);
    assertThat(claim.matchesIntention("CreatePerson", "fingerprint-a")).isTrue();
    assertThat(claim.matchesIntention("CreatePerson", "a-different-fingerprint")).isFalse();
  }

  @Test
  void markRejectedRecordsTheErrorCode() {
    String key = UUID.randomUUID().toString();
    UUID personId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    repository.claim("bff", key, "UpdatePerson", personId, "fingerprint-b", messageId, Instant.now());

    repository.markRejected("bff", key, "PERSON_VERSION_CONFLICT", Instant.now());

    IdempotencyRecord reread =
        repository.claim("bff", key, "UpdatePerson", personId, "fingerprint-b", UUID.randomUUID(), Instant.now());
    assertThat(reread.terminalStatus()).isEqualTo(IdempotencyStatus.REJECTED);
    assertThat(reread.errorCode()).isEqualTo("PERSON_VERSION_CONFLICT");
    assertThat(reread.aggregateVersion()).isNull();
  }

  @Test
  void differentProducersDoNotShareAClaimForTheSameIdempotencyKey() {
    String key = UUID.randomUUID().toString();
    UUID personId = UUID.randomUUID();

    IdempotencyRecord bffClaim =
        repository.claim("bff", key, "CreatePerson", personId, "fp", UUID.randomUUID(), Instant.now());
    IdempotencyRecord academicClaim =
        repository.claim("academic", key, "CreatePerson", personId, "fp", UUID.randomUUID(), Instant.now());

    assertThat(bffClaim.isNewClaim(bffClaim.claimedByMessageId())).isTrue();
    assertThat(academicClaim.isNewClaim(academicClaim.claimedByMessageId())).isTrue();
  }

  @Test
  void concurrentClaimsForTheSameKeyNeverBothBecomeTheNewClaim() throws Exception {
    String key = UUID.randomUUID().toString();
    UUID personId = UUID.randomUUID();
    UUID winnerMessageId = UUID.randomUUID();
    UUID loserMessageId = UUID.randomUUID();
    java.util.concurrent.CountDownLatch winnerClaimed = new java.util.concurrent.CountDownLatch(1);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<IdempotencyRecord> winner =
          executor.submit(
              () ->
                  tx.execute(
                      status -> {
                        IdempotencyRecord claim =
                            repository.claim(
                                "bff", key, "CreatePerson", personId, "fp", winnerMessageId, Instant.now());
                        winnerClaimed.countDown();
                        // Hold the row lock open long enough for the loser's
                        // claim() below to actually reach the DB and block on
                        // it, rather than racing to claim first itself.
                        sleepMillis(500);
                        repository.markSucceeded("bff", key, 1, Instant.now());
                        return claim;
                      }));

      Future<IdempotencyRecord> loser =
          executor.submit(
              () -> {
                winnerClaimed.await(5, TimeUnit.SECONDS);
                return repository.claim("bff", key, "CreatePerson", personId, "fp", loserMessageId, Instant.now());
              });

      IdempotencyRecord winnerResult = winner.get(10, TimeUnit.SECONDS);
      IdempotencyRecord loserResult = loser.get(10, TimeUnit.SECONDS);

      assertThat(winnerResult.isNewClaim(winnerMessageId)).isTrue();
      assertThat(loserResult.isNewClaim(loserMessageId)).isFalse();
      assertThat(loserResult.claimedByMessageId()).isEqualTo(winnerMessageId);
      // The loser's claim() only returns once the winner committed --
      // never mid-flight IN_PROGRESS.
      assertThat(loserResult.terminalStatus()).isEqualTo(IdempotencyStatus.SUCCEEDED);
      assertThat(loserResult.aggregateVersion()).isEqualTo(1);
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static void sleepMillis(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
