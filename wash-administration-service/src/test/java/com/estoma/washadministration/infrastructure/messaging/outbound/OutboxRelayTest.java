package com.estoma.washadministration.infrastructure.messaging.outbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.OutboxRepository.OutboxRecord;
import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageKind;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washadministration.infrastructure.messaging.MessagingProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class OutboxRelayTest {

  @Test
  void transientPublishFailureNeverBecomesFailedBecauseOfAttemptCount() {
    OutboxRepository outbox = mock(OutboxRepository.class);
    RoutingKeyResolver routing = mock(RoutingKeyResolver.class);
    ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
    EnvelopeJsonCodec codec = mock(EnvelopeJsonCodec.class);
    OutboundWirePayloadMapper wire = mock(OutboundWirePayloadMapper.class);
    PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    OutboxBackoffPolicy backoff = attempt -> Duration.ZERO;
    MessagingProperties properties = new MessagingProperties(
        Duration.ofSeconds(1), 1, 1_000L, true, 1_000L, false, Duration.ofSeconds(30));

    UUID messageId = UUID.randomUUID();
    OutboxRecord record = new OutboxRecord(
        messageId,
        "CabinActivated",
        1,
        "EVENT",
        "wash-administration",
        "CabinActivated",
        Instant.now(),
        null,
        null,
        UUID.randomUUID(),
        2,
        UUID.randomUUID(),
        null,
        "{\"type\":\"SYSTEM\",\"service\":\"wash-administration\"}",
        "{}",
        null,
        10_000,
        UUID.randomUUID());

    when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(outbox.claimBatch(eq(1), any(UUID.class), any(Instant.class))).thenReturn(List.of(record));
    when(routing.resolve(MessageKind.EVENT, "CabinActivated", "CabinActivated"))
        .thenReturn(new RoutingKeyResolver.Destination("platform.events", "wash-administration.cabin.activated"));
    when(codec.readActor(anyString())).thenReturn(Actor.system("wash-administration"));
    when(wire.map(MessageKind.EVENT, "CabinActivated", "{}"))
        .thenReturn(new ObjectMapper().createObjectNode());
    when(codec.encode(any())).thenReturn(new byte[] {'{', '}'});
    when(outbox.releaseForRetry(eq(messageId), any(UUID.class), any(Instant.class), eq("PUBLISH_TIMEOUT")))
        .thenReturn(true);
    doThrow(new RabbitPublishException(OutboxFailureCode.PUBLISH_TIMEOUT, UUID.randomUUID(), null))
        .when(publisher)
        .publishEvent(eq("platform.events"), eq("wash-administration.cabin.activated"), any());

    new OutboxRelay(outbox, routing, publisher, codec, wire, properties, backoff, tx).relay();

    verify(outbox).releaseForRetry(eq(messageId), any(UUID.class), any(Instant.class), eq("PUBLISH_TIMEOUT"));
    verify(outbox, never()).markFailed(eq(messageId), any(UUID.class), anyString());
  }
}
