package com.estoma.platformbff.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.OutboxRepository;
import com.estoma.platformbff.domain.messaging.OutboxMessage;
import com.estoma.platformbff.domain.messaging.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {
  @Mock OutboxRepository outbox;
  @Mock ConfirmedCommandPublisher publisher;

  @Test
  void claimsOneMessageAtATimeSoEveryPublishGetsAFreshLease() {
    var first = message(1);
    var second = message(1);
    when(outbox.claimBatch(anyString(), any(Instant.class), eq(1)))
        .thenReturn(List.of(first), List.of(second), List.of());
    when(outbox.markPublished(any(), any(), any())).thenReturn(true);
    var relay = new OutboxRelay(outbox, publisher, 50, 10);

    relay.relay();

    verify(outbox, times(3)).claimBatch(anyString(), any(Instant.class), eq(1));
    verify(publisher).publish(first);
    verify(publisher).publish(second);
  }

  @Test
  void exhaustedPublicationBecomesQuarantinedFailedOutbox() {
    var message = message(10);
    when(outbox.claimBatch(anyString(), any(Instant.class), eq(1)))
        .thenReturn(List.of(message));
    doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(message);
    when(outbox.markFailed(any(), any(), anyString(), isNull())).thenReturn(true);
    var relay = new OutboxRelay(outbox, publisher, 1, 10);

    relay.relay();

    verify(outbox).markFailed(
        eq(message.messageId()), eq(message.claimToken()), eq("PUBLISH_RETRY_EXHAUSTED"), isNull());
  }

  @Test
  void nonExhaustedPublicationGetsTechnicalRetry() {
    var message = message(2);
    when(outbox.claimBatch(anyString(), any(Instant.class), eq(1)))
        .thenReturn(List.of(message));
    doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(message);
    when(outbox.markFailed(any(), any(), anyString(), any(Instant.class))).thenReturn(true);
    var relay = new OutboxRelay(outbox, publisher, 1, 10);

    relay.relay();

    verify(outbox).markFailed(
        eq(message.messageId()), eq(message.claimToken()), eq("PUBLISH_FAILED"), any(Instant.class));
  }

  private static OutboxMessage message(int attempts) {
    Instant now = Instant.now();
    return new OutboxMessage(
        UUID.randomUUID(), "COMMAND", "CreatePerson", 1, "platform.commands", "users",
        UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), 1, "key", "{}", "{}",
        OutboxStatus.PENDING, UUID.randomUUID(), now.plusSeconds(30), attempts,
        null, null, null, now, now);
  }
}
