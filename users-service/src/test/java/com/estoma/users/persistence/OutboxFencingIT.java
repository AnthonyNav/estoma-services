package com.estoma.users.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.messaging.outbound.OutboxFailureCode;
import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The core Round 1 guarantee: once a worker's Outbox claim is gone
 * (lease expired and reclaimed by someone else), none of its fenced
 * transitions can affect the row anymore (docs/system-contract.md §8).
 * Worker A/B here are just two claims taken with the same repository —
 * fencing is enforced entirely by the {@code claim_token} column, not by
 * any in-process worker identity.
 */
class OutboxFencingIT extends AbstractPostgresIT {

  @Autowired private JdbcOutboxRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  void aWorkerThatLostItsLeaseCannotMarkTheRowPublished() {
    UUID messageId = append();
    OutboxRecord workerA = claimWithShortLease(messageId);

    reclaimExpiredLease();
    OutboxRecord workerB = claimSingle(messageId);
    assertThat(workerB.claimToken()).isNotEqualTo(workerA.claimToken());

    int rowsAffectedByA = repository.markPublished(messageId, workerA.claimToken(), Instant.now());

    assertThat(rowsAffectedByA).isZero();
    assertThat(statusOf(messageId)).isEqualTo("PROCESSING"); // still B's, untouched by A
    assertThat(claimTokenOf(messageId)).isEqualTo(workerB.claimToken());

    // B, the legitimate current owner, can still mark it published.
    int rowsAffectedByB = repository.markPublished(messageId, workerB.claimToken(), Instant.now());
    assertThat(rowsAffectedByB).isEqualTo(1);
    assertThat(statusOf(messageId)).isEqualTo("PUBLISHED");

    // A's failed transition is a claim-lost signal in the relay's usage
    // pattern (OutboxRelay/OutboxMetrics), not a persisted error -- this
    // repository-level test only verifies the DB contract itself: zero
    // rows affected, no write onto B's claim.
  }

  @Test
  void aWorkerThatLostItsLeaseCannotReleaseTheRowForRetryEither() {
    UUID messageId = append();
    OutboxRecord workerA = claimWithShortLease(messageId);

    // reclaimExpiredLease() itself performs a legitimate releaseForRetry
    // (as the sweeper would), which correctly does write LEASE_EXPIRED.
    // What must never happen next is A's own doomed retry overwriting
    // that with a stale code of its own.
    reclaimExpiredLease();
    OutboxRecord workerB = claimSingle(messageId);
    String errorCodeAfterReclaim = lastErrorCodeOf(messageId);

    int rowsAffectedByA =
        repository.releaseForRetry(messageId, workerA.claimToken(), Instant.now(), OutboxFailureCode.PUBLISH_NACK);

    assertThat(rowsAffectedByA).isZero();
    assertThat(statusOf(messageId)).isEqualTo("PROCESSING");
    assertThat(claimTokenOf(messageId)).isEqualTo(workerB.claimToken());
    // A's stale attempt must never overwrite whatever error code B's claim carries.
    assertThat(lastErrorCodeOf(messageId)).isEqualTo(errorCodeAfterReclaim);
  }

  @Test
  void aWorkerThatLostItsLeaseCannotMarkTheRowFailedEither() {
    UUID messageId = append();
    OutboxRecord workerA = claimWithShortLease(messageId);

    reclaimExpiredLease();
    OutboxRecord workerB = claimSingle(messageId);

    int rowsAffectedByA = repository.markFailed(messageId, workerA.claimToken(), OutboxFailureCode.PUBLISH_NACK);

    assertThat(rowsAffectedByA).isZero();
    assertThat(statusOf(messageId)).isEqualTo("PROCESSING");
    assertThat(claimTokenOf(messageId)).isEqualTo(workerB.claimToken());
  }

  /** Worker A's short-lived claim, its lease already expired by construction. */
  private OutboxRecord claimWithShortLease(UUID messageId) {
    return repository.claimBatch(20, Duration.ofMillis(1)).stream()
        .filter(r -> r.messageId().equals(messageId))
        .findFirst()
        .orElseThrow();
  }

  /** Deterministically does what OutboxSweeper's scheduled pass would, without waiting on its 30s schedule. */
  private void reclaimExpiredLease() {
    sleepPastLease();
    List<ExpiredLease> expired = repository.findExpiredLeases(50);
    for (ExpiredLease lease : expired) {
      repository.releaseForRetry(lease.messageId(), lease.claimToken(), Instant.now(), OutboxFailureCode.LEASE_EXPIRED);
    }
  }

  private static void sleepPastLease() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private OutboxRecord claimSingle(UUID messageId) {
    return repository.claimBatch(20, Duration.ofSeconds(30)).stream()
        .filter(r -> r.messageId().equals(messageId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected " + messageId + " to be reclaimable by worker B"));
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

  private String lastErrorCodeOf(UUID messageId) {
    return jdbc.sql("SELECT last_error_code FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(String.class)
        .optional()
        .orElse(null);
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
   * active at that later, unrelated point in time. {@code
   * RoutingKeyResolver} only recognizes the four real event types, so an
   * unregistered one fails fast inside the relay's publish attempt
   * (IllegalArgumentException, caught as PUBLISH_UNEXPECTED_ERROR) before
   * any AMQP call is ever made -- these rows can never reach a real queue.
   */
  private static final String TEST_MESSAGE_TYPE = "ItOutboxFencingTestRow";

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
