package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Refresh;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReassignmentCandidatesQueryPublisherTest {
  @Test
  void publishesExactV1QueryToWashExecutionWithBffSystemActor() {
    JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    MessageEnvelopeCodec codec = new MessageEnvelopeCodec(mapper);
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    doAnswer(invocation -> {
      CorrelationData confirmation = invocation.getArgument(3);
      confirmation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbit).send(eq("platform.queries"), eq("wash-execution"), any(Message.class),
        any(CorrelationData.class));
    UUID executionId = UUID.randomUUID();
    Refresh refresh = new Refresh(executionId, 3, UUID.randomUUID(), UUID.randomUUID(), "PENDING",
        null, null, 0, null, null, null, Instant.parse("2026-09-05T18:00:00Z"));

    new ReassignmentCandidatesQueryPublisher(rabbit, codec, Duration.ofSeconds(1)).publish(refresh);

    var sent = ArgumentCaptor.forClass(Message.class);
    verify(rabbit).send(eq("platform.queries"), eq("wash-execution"), sent.capture(),
        any(CorrelationData.class));
    JsonNode envelope = mapper.readValue(sent.getValue().getBody(), JsonNode.class);
    assertThat(envelope.path("messageType").asText()).isEqualTo("GetReassignmentCandidates");
    assertThat(envelope.path("messageVersion").asInt()).isEqualTo(1);
    assertThat(envelope.path("messageKind").asText()).isEqualTo("QUERY");
    assertThat(envelope.path("producer").asText()).isEqualTo("bff");
    assertThat(envelope.path("correlationId").asText()).isEqualTo(refresh.correlationId().toString());
    assertThat(envelope.path("actor").path("type").asText()).isEqualTo("SYSTEM");
    assertThat(envelope.path("actor").path("service").asText()).isEqualTo("bff");
    assertThat(envelope.path("actor").size()).isEqualTo(2);
    assertThat(envelope.path("payload").size()).isEqualTo(1);
    assertThat(envelope.path("payload").path("washExecutionId").asText())
        .isEqualTo(executionId.toString());
  }
}
