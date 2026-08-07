package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.config.OutboxProperties;
import com.estoma.users.persistence.JdbcOutboxRepository;
import com.estoma.users.persistence.OutboxRecord;
import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Exercises the real {@code OutboxSweeper} bean directly (its public
 * method, not waiting on its 30s {@code @Scheduled} trigger): a
 * PROCESSING row whose lease has expired and whose retry budget is
 * exhausted must be marked FAILED with {@code LEASE_EXPIRED}
 * (docs/system-contract.md §8).
 */
class OutboxSweeperIT extends AbstractPostgresIT {

  @Autowired private OutboxSweeper sweeper;
  @Autowired private JdbcOutboxRepository outboxRepository;
  @Autowired private OutboxProperties outboxProperties;
  @Autowired private JdbcClient jdbc;

  @Test
  void anExpiredLeaseWithNoAttemptsLeftIsMarkedFailedWithLeaseExpired() {
    UUID messageId = append();
    OutboxRecord claimed = claimWithAnAlreadyExpiredLease(messageId);
    // Fast-forward straight to the attempt ceiling so the sweeper's first
    // pass marks it FAILED instead of releasing it for another retry.
    jdbc.sql("UPDATE outbox_messages SET attempts = :attempts WHERE message_id = :id")
        .param("attempts", outboxProperties.maxAttempts())
        .param("id", messageId)
        .update();
    sleepPastLease();

    sweepUntilReclaimed(messageId);

    assertThat(statusOf(messageId)).isEqualTo("FAILED");
    assertThat(lastErrorCodeOf(messageId)).isEqualTo("LEASE_EXPIRED");
    assertThat(lastErrorAtOf(messageId)).isNotNull();
    assertThat(claimTokenOf(messageId)).isNull();
    assertThat(leaseUntilOf(messageId)).isNull();
    assertThat(claimed.claimToken()).isNotNull(); // the claim this test set up did exist
  }

  @Test
  void anExpiredLeaseWithAttemptsRemainingIsReleasedForRetryInstead() {
    UUID messageId = append();
    claimWithAnAlreadyExpiredLease(messageId);
    sleepPastLease();

    sweepUntilReclaimed(messageId);

    assertThat(statusOf(messageId)).isEqualTo("PENDING");
    assertThat(lastErrorCodeOf(messageId)).isEqualTo("LEASE_EXPIRED");
    assertThat(availableAtOf(messageId)).isAfter(OffsetDateTime.now());
  }

  private OutboxRecord claimWithAnAlreadyExpiredLease(UUID messageId) {
    return outboxRepository.claimBatch(20, Duration.ofMillis(1)).stream()
        .filter(r -> r.messageId().equals(messageId))
        .findFirst()
        .orElseThrow();
  }

  /**
   * {@code releaseExpiredLeases()} only reclaims the oldest {@code
   * claim-batch-size} expired-lease rows per call (ORDER BY lease_until
   * LIMIT :limit, {@code JdbcOutboxRepository.findExpiredLeases}).
   * {@code AbstractPostgresIT} shares one Postgres instance and one
   * Spring context across every {@code *IT} in the suite, and several
   * other classes (e.g. {@code OutboxFencingIT}, {@code
   * ConcurrentRepublishIT}) deliberately leave PROCESSING rows with
   * expired leases behind. Under a full {@code mvn verify} run that
   * backlog can exceed the batch size by the time this test runs,
   * starving this test's own (freshly claimed, so newest-leased) row out
   * of a single pass -- not a fencing bug, just batch-size contention on
   * shared state. Looping drains the backlog instead of assuming one
   * call is enough, without weakening what the test actually asserts.
   */
  private void sweepUntilReclaimed(UUID messageId) {
    for (int i = 0; i < 50 && "PROCESSING".equals(statusOf(messageId)); i++) {
      sweeper.releaseExpiredLeases();
    }
  }

  /** A real (short) sleep, so a 1ms lease is unambiguously in the past by the time the sweeper looks for it. */
  private static void sleepPastLease() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private String statusOf(UUID messageId) {
    return jdbc.sql("SELECT status FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(String.class)
        .single();
  }

  private String lastErrorCodeOf(UUID messageId) {
    return jdbc.sql("SELECT last_error_code FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private OffsetDateTime lastErrorAtOf(UUID messageId) {
    return jdbc.sql("SELECT last_error_at FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(OffsetDateTime.class)
        .optional()
        .orElse(null);
  }

  private UUID claimTokenOf(UUID messageId) {
    return jdbc.sql("SELECT claim_token FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  private OffsetDateTime leaseUntilOf(UUID messageId) {
    return jdbc.sql("SELECT lease_until FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(OffsetDateTime.class)
        .optional()
        .orElse(null);
  }

  private OffsetDateTime availableAtOf(UUID messageId) {
    return jdbc.sql("SELECT available_at FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(OffsetDateTime.class)
        .single();
  }

  /**
   * A deliberately unregistered {@code messageType} -- see {@code
   * OutboxFencingIT}'s constant of the same purpose. This class runs
   * against {@code AbstractPostgresIT} (no working RabbitMQ in its own
   * context), but the live {@code OutboxRelay} in some OTHER,
   * RabbitMQ-connected test context could still discover a row like this
   * later, since Postgres is shared. A real event type would then
   * actually reach {@code identity.person.*} and pollute an unrelated
   * test's wildcard-bound queue; an unregistered one fails inside {@code
   * RoutingKeyResolver} before any AMQP call happens.
   */
  private static final String TEST_MESSAGE_TYPE = "ItOutboxSweeperTestRow";

  private UUID append() {
    UUID messageId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    outboxRepository.append(
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
