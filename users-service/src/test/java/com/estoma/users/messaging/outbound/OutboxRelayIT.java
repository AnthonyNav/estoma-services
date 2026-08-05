package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.config.OutboxProperties;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.OutboxMetadata;
import com.estoma.users.persistence.JdbcOutboxRepository;
import com.estoma.users.testsupport.AbstractMessagingIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the real, application-wired {@code OutboxRelay} (the {@code
 * @Scheduled} bean, not a hand-built instance) against a real broker:
 * RESULT publications must be routable to count as published, EVENT
 * publications must not (docs/system-contract.md §7, §8).
 */
class OutboxRelayIT extends AbstractMessagingIT {

  @Autowired private JdbcOutboxRepository outboxRepository;
  @Autowired private JdbcClient jdbc;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private OutboxProperties outboxProperties;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void anUnroutableResultNeverReachesPublishedAndRecordsWhyItFailed() {
    double failedPublishBefore = counter("estoma.outbox.failed_publish");
    double publishedBefore = counter("estoma.outbox.published");
    UUID messageId = appendResult("it.no-binding." + UUID.randomUUID());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(lastErrorCodeOf(messageId)).isEqualTo("PUBLISH_RETURNED"));

    assertThat(statusOf(messageId)).isEqualTo("PENDING");
    assertThat(availableAtOf(messageId)).isAfter(OffsetDateTime.now());
    assertThat(counter("estoma.outbox.failed_publish")).isGreaterThan(failedPublishBefore);
    assertThat(counter("estoma.outbox.published")).isEqualTo(publishedBefore);
  }

  @Test
  void anEventWithNoBoundConsumerStillReachesPublished() {
    UUID messageId = appendEvent();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(messageId)).isEqualTo("PUBLISHED"));

    assertThat(lastErrorCodeOf(messageId)).isNull();
    assertThat(lastErrorAtOf(messageId)).isNull();
  }

  @Test
  void anUnroutableResultThatHasExhaustedItsAttemptsIsMarkedFailed() {
    UUID messageId = appendResult("it.no-binding." + UUID.randomUUID());
    // Fast-forward this row to one claim away from exhausting its retry
    // budget, so the test doesn't have to wait through the full backoff
    // ladder to observe the FAILED transition.
    jdbc.sql("UPDATE outbox_messages SET attempts = :attempts WHERE message_id = :id")
        .param("attempts", outboxProperties.maxAttempts() - 1)
        .param("id", messageId)
        .update();

    // Generous timeout: if the live relay's first claim races ahead of
    // the attempts fast-forward above, this row goes through one more
    // full 5s-backoff retry cycle before the second claim lands on
    // attempts = maxAttempts and marks it FAILED.
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(statusOf(messageId)).isEqualTo("FAILED"));

    assertThat(lastErrorCodeOf(messageId)).isEqualTo("PUBLISH_RETURNED");
  }

  private UUID appendResult(String unboundDestination) {
    UUID messageId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    OutboxMetadata metadata = new OutboxMetadata(unboundDestination, UUID.randomUUID(), UUID.randomUUID());
    outboxRepository.append(
        new OutboxAppendRequest(
            messageId,
            "CreatePersonSucceeded",
            1,
            "RESULT",
            personId,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "{\"personId\":\"" + personId + "\",\"aggregateVersion\":1}",
            codec.writeValueAsString(metadata),
            Instant.now()));
    return messageId;
  }

  private UUID appendEvent() {
    UUID messageId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    outboxRepository.append(
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

  private OffsetDateTime availableAtOf(UUID messageId) {
    return jdbc.sql("SELECT available_at FROM outbox_messages WHERE message_id = :id")
        .param("id", messageId)
        .query(OffsetDateTime.class)
        .single();
  }

  private double counter(String name) {
    var counter = meterRegistry.find(name).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
