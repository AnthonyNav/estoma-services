package com.estoma.authorization.infrastructure.messaging.outbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.OutboxRepository.OutboxMessage;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authorization.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class OutboxRelayTest {

  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

  @Test
  void brokerFailureReleasesEvenAnOftenRetriedMessageForFencedRetry() {
    OutboxRepository outbox = mock(OutboxRepository.class);
    ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
    PlatformTransactionManager transactions = transactions();
    UUID messageId = UUID.randomUUID();
    OutboxMessage message = event(messageId, 99);
    when(outbox.claimBatch(eq(1), any(UUID.class), any(Instant.class), eq(NOW))).thenReturn(List.of(message));
    when(outbox.releaseForRetry(
            eq(messageId), any(UUID.class), eq(NOW.plusSeconds(100)), eq("PUBLISH_CONNECTION_FAILURE"), anyString()))
        .thenReturn(true);
    doThrow(new RabbitPublishException(
            PublishFailureCode.PUBLISH_CONNECTION_FAILURE, UUID.randomUUID(), null))
        .when(publisher)
        .publishEvent(eq("platform.events"), eq("authorization.account-role.assigned"), any());

    relay(outbox, publisher, transactions, attempt -> Duration.ofSeconds(attempt)).relay();

    verify(outbox).releaseForRetry(
        eq(messageId), any(UUID.class), eq(NOW.plusSeconds(100)), eq("PUBLISH_CONNECTION_FAILURE"), anyString());
    verify(outbox, never()).markFailed(eq(messageId), any(UUID.class), anyString(), anyString());
    verify(outbox, never()).markPublished(eq(messageId), any(UUID.class), any(Instant.class));
  }

  @Test
  void aLostPostPublishClaimIsNotConvertedIntoAnotherPublicationTransition() {
    OutboxRepository outbox = mock(OutboxRepository.class);
    ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
    PlatformTransactionManager transactions = transactions();
    UUID messageId = UUID.randomUUID();
    when(outbox.claimBatch(eq(1), any(UUID.class), any(Instant.class), eq(NOW))).thenReturn(List.of(event(messageId, 1)));
    when(outbox.markPublished(eq(messageId), any(UUID.class), eq(NOW))).thenReturn(false);

    relay(outbox, publisher, transactions, attempt -> Duration.ZERO).relay();

    verify(outbox).markPublished(eq(messageId), any(UUID.class), eq(NOW));
    verify(outbox, never()).releaseForRetry(
        eq(messageId), any(UUID.class), any(Instant.class), anyString(), anyString());
    verify(outbox, never()).markFailed(eq(messageId), any(UUID.class), anyString(), anyString());
  }

  private static OutboxRelay relay(
      OutboxRepository outbox,
      ConfirmedRabbitPublisher publisher,
      PlatformTransactionManager transactions,
      OutboxBackoffPolicy backoff) {
    return new OutboxRelay(
        outbox,
        publisher,
        new EnvelopeJsonCodec(JsonMapper.builder().build()),
        new MessagingProperties(
            true,
            true,
            Duration.ofSeconds(1),
            1,
            Duration.ofMinutes(2),
            Duration.ofSeconds(1),
            false,
            Duration.ofSeconds(1),
            false,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30)),
        backoff,
        transactions,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new SimpleMeterRegistry());
  }

  private static PlatformTransactionManager transactions() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return transactions;
  }

  private static OutboxMessage event(UUID messageId, int attempts) {
    return new OutboxMessage(
        messageId,
        "AccountRoleAssigned",
        1,
        "EVENT",
        "authorization",
        "authorization.account-role.assigned",
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        ActorRef.system("authorization"),
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "{}",
        "PROCESSING",
        attempts,
        NOW,
        NOW,
        null,
        null,
        null,
        UUID.randomUUID(),
        NOW,
        NOW.plusSeconds(120));
  }
}
