package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.config.OutboxProperties;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.observability.OutboxMetrics;
import com.estoma.users.persistence.JdbcOutboxRepository;
import com.estoma.users.persistence.OutboxRecord;
import com.estoma.users.persistence.OutboxStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies {@link OutboxRelay}'s per-row processing, and specifically the
 * interruption contract (docs/system-contract.md §7, Round 1): a single
 * fenced transition for the row that was interrupted, then the relay
 * abandons the rest of the claimed batch rather than continuing on an
 * interrupted thread.
 */
class OutboxRelayTest {

  private final JdbcOutboxRepository outboxRepository = mock(JdbcOutboxRepository.class);
  private final RoutingKeyResolver routingKeyResolver = new RoutingKeyResolver();
  private final ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final OutboxProperties properties =
      new OutboxProperties(10, Duration.ofMinutes(2), 10, Duration.ofDays(7), Duration.ofDays(30), List.of(Duration.ofSeconds(5)));
  private final OutboxBackoffPolicy backoffPolicy = attempts -> Duration.ofSeconds(5);
  private final OutboxMetrics metrics = mock(OutboxMetrics.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  private final OutboxRelay relay =
      new OutboxRelay(outboxRepository, routingKeyResolver, publisher, codec, properties, backoffPolicy, metrics, clock);

  @Test
  void interruptionAbandonsTheRestOfTheBatchAfterOneFencedTransition() {
    OutboxRecord interrupted = eventRecord("PersonCreated");
    OutboxRecord neverProcessed = eventRecord("PersonUpdated");
    when(outboxRepository.claimBatch(properties.claimBatchSize(), properties.lease()))
        .thenReturn(List.of(interrupted, neverProcessed));
    doThrow(new RabbitPublishException(OutboxFailureCode.PUBLISH_INTERRUPTED, UUID.randomUUID(), null))
        .when(publisher)
        .publishEvent(eq(AmqpNames.EXCHANGE_EVENTS), eq(AmqpNames.ROUTING_KEY_PERSON_CREATED), any(Message.class));
    when(outboxRepository.releaseForRetry(
            eq(interrupted.messageId()), eq(interrupted.claimToken()), any(Instant.class), eq(OutboxFailureCode.PUBLISH_INTERRUPTED)))
        .thenReturn(1);

    try {
      // No need to interrupt this thread ourselves -- the mocked
      // publisher unconditionally reports PUBLISH_INTERRUPTED, exactly
      // as DefaultConfirmedRabbitPublisher would after actually observing
      // an InterruptedException. What's under test is OutboxRelay's
      // reaction to that code, including restoring the flag itself.
      relay.relay();

      // The row that failed gets exactly one fenced transition attempt.
      verify(outboxRepository)
          .releaseForRetry(
              eq(interrupted.messageId()),
              eq(interrupted.claimToken()),
              any(Instant.class),
              eq(OutboxFailureCode.PUBLISH_INTERRUPTED));

      // The rest of the batch is never touched -- no publish attempt, no transition.
      verify(publisher, never())
          .publishEvent(eq(AmqpNames.EXCHANGE_EVENTS), eq(AmqpNames.ROUTING_KEY_PERSON_UPDATED), any(Message.class));
      verify(outboxRepository, never())
          .markPublished(eq(neverProcessed.messageId()), any(UUID.class), any(Instant.class));
      verify(outboxRepository, never())
          .releaseForRetry(eq(neverProcessed.messageId()), any(UUID.class), any(Instant.class), any(OutboxFailureCode.class));
      verify(outboxRepository, never())
          .markFailed(eq(neverProcessed.messageId()), any(UUID.class), any(OutboxFailureCode.class));

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear, so this doesn't leak into other tests
    }
  }

  @Test
  void aConfirmedPublicationMarksTheRowPublishedOnlyOnce() {
    OutboxRecord record = eventRecord("PersonCreated");
    when(outboxRepository.claimBatch(properties.claimBatchSize(), properties.lease())).thenReturn(List.of(record));
    when(outboxRepository.markPublished(record.messageId(), record.claimToken(), clock.instant())).thenReturn(1);

    relay.relay();

    verify(publisher)
        .publishEvent(eq(AmqpNames.EXCHANGE_EVENTS), eq(AmqpNames.ROUTING_KEY_PERSON_CREATED), any(Message.class));
    verify(outboxRepository).markPublished(record.messageId(), record.claimToken(), clock.instant());
    verify(metrics).published();
    verify(metrics, never()).claimLost();
  }

  @Test
  void aLostClaimOnMarkPublishedIsNeverRetriedAndDoesNotCountAsFailedPublish() {
    OutboxRecord record = eventRecord("PersonCreated");
    when(outboxRepository.claimBatch(properties.claimBatchSize(), properties.lease())).thenReturn(List.of(record));
    when(outboxRepository.markPublished(record.messageId(), record.claimToken(), clock.instant())).thenReturn(0);

    relay.relay();

    verify(metrics).claimLost();
    verify(metrics, never()).published();
    verify(metrics, never()).failedPublish();
    verify(outboxRepository).markPublished(record.messageId(), record.claimToken(), clock.instant());
  }

  private static OutboxRecord eventRecord(String messageType) {
    UUID messageId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    return new OutboxRecord(
        messageId,
        messageType,
        1,
        "EVENT",
        aggregateId,
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "{}",
        null,
        OutboxStatus.PROCESSING,
        1,
        UUID.randomUUID(),
        Instant.now().plusSeconds(120),
        null,
        null,
        Instant.now(),
        Instant.now(),
        null);
  }
}
