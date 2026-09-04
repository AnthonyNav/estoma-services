package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.estoma.platformbff.domain.projection.RecoveryQuery;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DefaultRecoveryQueryPublisherTest {
  private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
  private final MessageEnvelopeCodec codec = new MessageEnvelopeCodec(mapper);

  @Test
  void usesBffSystemActorForLegacyRecoveryQueryWithoutPersistedActor() {
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    doAnswer(invocation -> {
      CorrelationData confirmation = invocation.getArgument(3);
      confirmation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbit).send(eq("platform.queries"), eq("users"), any(Message.class), any(CorrelationData.class));

    var publisher = new DefaultRecoveryQueryPublisher(rabbit, codec, Duration.ofSeconds(1));
    publisher.publish(query(null));

    var sent = ArgumentCaptor.forClass(Message.class);
    verify(rabbit).send(eq("platform.queries"), eq("users"), sent.capture(), any(CorrelationData.class));
    JsonNode envelope = mapper.readValue(sent.getValue().getBody(), JsonNode.class);
    assertThat(envelope.path("producer").asText()).isEqualTo("bff");
    assertThat(envelope.path("actor").path("type").asText()).isEqualTo("SYSTEM");
    assertThat(envelope.path("actor").path("service").asText()).isEqualTo("bff");
    assertThat(envelope.path("actor").size()).isEqualTo(2);
    assertThat(envelope.path("idempotencyKey").isNull()).isTrue();
  }

  private static RecoveryQuery query(String actorJson) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    return new RecoveryQuery(id, UUID.randomUUID(), 1, "ListPersonPresentationSnapshotsPage", 1,
        "platform.queries", "users", UUID.randomUUID(), null, id.toString(), actorJson,
        Map.of("limit", 200), "PENDING", null, null, 0, null, null, null, now, now);
  }
}
