package com.estoma.communications.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.communications.contracts.Actor;
import com.estoma.communications.infrastructure.persistence.JdbcOutboxRelayRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OutboxRelayTest {

  private final JdbcOutboxRelayRepository repository = mock(JdbcOutboxRelayRepository.class);
  private final ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
  private final CommunicationsEventRoutingKeyResolver routing = new CommunicationsEventRoutingKeyResolver();
  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(new ObjectMapper());
  private final OutboxRelay relay = new OutboxRelay(repository, codec, publisher, routing);

  @Test
  void resultUsesRequiredRouteAndIsMarkedPublishedOnlyAfterPublishReturns() {
    var row = row("RequestCommunicationSucceeded", "RESULT", "bff");
    when(repository.claim(eq(50), any(Instant.class))).thenReturn(List.of(row));

    relay.relay();

    verify(publisher).publishRequiredRoute(eq("platform.results"), eq("bff"), any());
    verify(repository).markPublished(eq(row.messageId()), any(Instant.class));
  }

  @Test
  void eventUsesCanonicalTopicRouting() {
    var row = row("CommunicationRequested", "EVENT", null);
    when(repository.claim(eq(50), any(Instant.class))).thenReturn(List.of(row));

    relay.relay();

    verify(publisher).publishEvent(eq("platform.events"), eq("communications.communication.requested"), any());
    verify(repository).markPublished(eq(row.messageId()), any(Instant.class));
  }

  @Test
  void unconfirmedPublicationReturnsRowToDurableBackoff() {
    var row = row("CommunicationRequested", "EVENT", null);
    when(repository.claim(eq(50), any(Instant.class))).thenReturn(List.of(row));
    doThrow(new IllegalStateException("confirm timeout"))
        .when(publisher).publishEvent(eq("platform.events"), eq("communications.communication.requested"), any());

    relay.relay();

    verify(repository).markPublicationFailure(eq(row.messageId()), eq(row.attempts()), any(Instant.class));
  }

  private static JdbcOutboxRelayRepository.OutboxRow row(String type, String kind, String destination) {
    Instant now = Instant.now();
    return new JdbcOutboxRelayRepository.OutboxRow(
        UUID.randomUUID(), type, 1, kind, UUID.randomUUID(), 1,
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        Actor.system("communications"), destination, new ObjectMapper().createObjectNode(), now, 1);
  }
}
