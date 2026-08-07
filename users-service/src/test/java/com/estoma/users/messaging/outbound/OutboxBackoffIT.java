package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.persistence.JdbcOutboxRepository;
import com.estoma.users.persistence.OutboxRecord;
import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the backoff ladder actually reaches the database, end to end:
 * an immediate reclaim is blocked until the persisted {@code
 * available_at} has passed, and repeated failures on the same row
 * progress through the configured delays (docs/system-contract.md §8).
 * The ladder's own values are unit-tested in {@code
 * ConfiguredOutboxBackoffPolicyTest}; this class is about the repository
 * honoring whatever the policy computed, without real multi-minute
 * sleeps.
 */
class OutboxBackoffIT extends AbstractPostgresIT {

  @Autowired private JdbcOutboxRepository repository;
  @Autowired private OutboxBackoffPolicy backoffPolicy;
  @Autowired private JdbcClient jdbc;

  @Test
  void immediateReclaimIsBlockedUntilTheBackoffDelayHasPassed() {
    UUID messageId = append();
    OutboxRecord claim = claimSingle(messageId);
    Duration delay = backoffPolicy.delayForAttempt(claim.attempts());

    repository.releaseForRetry(messageId, claim.claimToken(), Instant.now().plus(delay), OutboxFailureCode.PUBLISH_NACK);

    assertThat(claimAttempt(messageId)).isEmpty();

    movePast(messageId);

    assertThat(claimAttempt(messageId)).isPresent();
  }

  @Test
  void thePersistedDelayProgressesThroughTheConfiguredLadderAcrossRepeatedFailures() {
    UUID messageId = append();

    for (int attempt = 1; attempt <= 3; attempt++) {
      OutboxRecord claim = claimSingle(messageId);
      assertThat(claim.attempts()).isEqualTo(attempt);

      Duration expectedDelay = backoffPolicy.delayForAttempt(claim.attempts());
      Instant releasedAt = Instant.now();
      repository.releaseForRetry(messageId, claim.claimToken(), releasedAt.plus(expectedDelay), OutboxFailureCode.PUBLISH_NACK);

      Instant persistedAvailableAt = availableAtOf(messageId);
      Duration actualDelay = Duration.between(releasedAt, persistedAvailableAt);
      assertThat(actualDelay.minus(expectedDelay).abs()).isLessThan(Duration.ofSeconds(2));

      // Make it immediately reclaimable for the next loop iteration --
      // no real multi-minute sleep to observe the 30s/2m ladder steps.
      movePast(messageId);
    }
  }

  private Optional<OutboxRecord> claimAttempt(UUID messageId) {
    return repository.claimBatch(20, Duration.ofSeconds(30)).stream()
        .filter(r -> r.messageId().equals(messageId))
        .findFirst();
  }

  private OutboxRecord claimSingle(UUID messageId) {
    return claimAttempt(messageId).orElseThrow(() -> new AssertionError("expected " + messageId + " to be claimable"));
  }

  private void movePast(UUID messageId) {
    jdbc.sql("UPDATE outbox_messages SET available_at = :past WHERE message_id = :id")
        .param("past", OffsetDateTime.now().minusSeconds(1))
        .param("id", messageId)
        .update();
  }

  private Instant availableAtOf(UUID messageId) {
    return jdbc.sql("SELECT available_at FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }

  /**
   * A deliberately unregistered {@code messageType} -- see {@code
   * OutboxFencingIT}'s constant of the same purpose: this class runs
   * against {@code AbstractPostgresIT} (no working RabbitMQ in its own
   * context), but the shared Postgres container means some OTHER,
   * RabbitMQ-connected test context's live {@code OutboxRelay} could
   * still discover a row like this later. A real event type would then
   * actually reach {@code identity.person.*} and pollute an unrelated
   * test's wildcard-bound queue; an unregistered one fails inside {@code
   * RoutingKeyResolver} before any AMQP call happens.
   */
  private static final String TEST_MESSAGE_TYPE = "ItOutboxBackoffTestRow";

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
