package com.estoma.authentication.infrastructure.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.messaging.MessagingProperties;
import com.estoma.authentication.infrastructure.persistence.JdbcOutboxRepository;
import com.estoma.authentication.infrastructure.persistence.OutboxRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.json.JsonMapper;

class OutboxRelayActorTest {

  @Test
  void stripsAuthorizationContextFromAccountActorsOnEvents() {
    UUID accountId = UUID.randomUUID();
    Actor durableCommandActor = new Actor("ACCOUNT", accountId, null, "GLOBAL", "wash-system");

    Actor wire = OutboxRelay.wireActor(MessageKind.EVENT, durableCommandActor);

    assertThat(wire).isEqualTo(new Actor(accountId));
  }

  @Test
  void preservesTheFullActorOnResults() {
    Actor durableCommandActor = new Actor("ACCOUNT", UUID.randomUUID(), null, "GLOBAL", null);

    assertThat(OutboxRelay.wireActor(MessageKind.RESULT, durableCommandActor))
        .isSameAs(durableCommandActor);
  }

  @Test
  void preservesSystemActorsOnEvents() {
    Actor systemActor = new Actor("SYSTEM", null, "academic", null, null);

    assertThat(OutboxRelay.wireActor(MessageKind.EVENT, systemActor)).isSameAs(systemActor);
  }

  @Test
  void relayPublishesCanonicalAccountEventActorsAndPreservesResultScope() {
    JdbcOutboxRepository repository = mock(JdbcOutboxRepository.class);
    ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
    JsonMapper mapper = JsonMapper.builder().build();
    EnvelopeJsonCodec codec = new EnvelopeJsonCodec(mapper);
    Actor storedActor = new Actor("ACCOUNT", UUID.randomUUID(), null, "GLOBAL", null);
    when(repository.claimBatch(10)).thenReturn(List.of(
        row("AccountCreated", "EVENT", null, storedActor),
        row("AccountActivated", "EVENT", null, storedActor),
        row("CreateAccountSucceeded", "RESULT", "bff", storedActor)));
    OutboxRelay relay = new OutboxRelay(
        repository, new RoutingKeyResolver(), publisher, codec,
        new MessagingProperties(Duration.ofSeconds(1), 10, 3, true), attempts -> Duration.ZERO);

    relay.relay();

    ArgumentCaptor<Message> eventMessages = ArgumentCaptor.forClass(Message.class);
    verify(publisher, org.mockito.Mockito.times(2))
        .publishEvent(anyString(), anyString(), eventMessages.capture());
    for (Message message : eventMessages.getAllValues()) {
      var actor = mapper.readTree(message.getBody()).get("actor");
      assertThat(actor.propertyNames()).containsExactlyInAnyOrder("type", "accountId");
      assertThat(actor.get("type").asString()).isEqualTo("ACCOUNT");
      assertThat(actor.get("accountId").asString()).isEqualTo(storedActor.accountId().toString());
    }
    ArgumentCaptor<Message> resultMessage = ArgumentCaptor.forClass(Message.class);
    verify(publisher).publishRequiredRoute(anyString(), anyString(), resultMessage.capture());
    assertThat(mapper.readTree(resultMessage.getValue().getBody()).path("actor").path("scope").asString())
        .isEqualTo("GLOBAL");
    verify(repository, org.mockito.Mockito.times(3)).markPublished(any(UUID.class), any(Instant.class));
  }

  private static OutboxRecord row(String type, String kind, String requester, Actor actor) {
    return new OutboxRecord(
        UUID.randomUUID(), type, 1, kind, UUID.randomUUID(), 1,
        UUID.randomUUID(), UUID.randomUUID(), null, null, actor, requester, "{}", 0, Instant.now());
  }
}
