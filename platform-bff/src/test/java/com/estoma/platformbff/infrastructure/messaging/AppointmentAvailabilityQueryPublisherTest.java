package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Context;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Refresh;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AppointmentAvailabilityQueryPublisherTest {
  private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
  private final MessageEnvelopeCodec codec = new MessageEnvelopeCodec(mapper);

  @Test
  void publishesTheExactOwnerQueryWithBffSystemActor() {
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    doAnswer(invocation -> {
      CorrelationData confirmation = invocation.getArgument(3);
      confirmation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbit).send(eq("platform.queries"), eq("appointments"), any(Message.class),
        any(CorrelationData.class));
    var context = new Context(UUID.randomUUID(), LocalDate.of(2026, 9, 4), "NORMAL", 15,
        "HIGH_SPEED", UUID.randomUUID());
    Refresh refresh = Refresh.pending(context, Instant.parse("2026-09-04T18:00:00Z"));

    new AppointmentAvailabilityQueryPublisher(rabbit, codec, Duration.ofSeconds(1))
        .publish(refresh);

    var sent = ArgumentCaptor.forClass(Message.class);
    verify(rabbit).send(eq("platform.queries"), eq("appointments"), sent.capture(),
        any(CorrelationData.class));
    JsonNode envelope = mapper.readValue(sent.getValue().getBody(), JsonNode.class);
    assertThat(envelope.path("messageId").asText()).isEqualTo(refresh.messageId().toString());
    assertThat(envelope.path("messageType").asText()).isEqualTo("GetAppointmentAvailability");
    assertThat(envelope.path("messageVersion").asInt()).isEqualTo(1);
    assertThat(envelope.path("messageKind").asText()).isEqualTo("QUERY");
    assertThat(envelope.path("producer").asText()).isEqualTo("bff");
    assertThat(envelope.path("correlationId").asText())
        .isEqualTo(refresh.correlationId().toString());
    assertThat(envelope.path("causationId").isNull()).isTrue();
    assertThat(envelope.path("operationId").isNull()).isTrue();
    assertThat(envelope.path("idempotencyKey").isNull()).isTrue();
    assertThat(envelope.path("aggregateId").isNull()).isTrue();
    assertThat(envelope.path("aggregateVersion").isNull()).isTrue();
    assertThat(envelope.path("actor").path("type").asText()).isEqualTo("SYSTEM");
    assertThat(envelope.path("actor").path("service").asText()).isEqualTo("bff");
    assertThat(envelope.path("actor").size()).isEqualTo(2);
    assertThat(toSet(envelope.path("payload")))
        .containsExactlyInAnyOrder("studentAccountId", "serviceDate", "appointmentType",
            "instrumentCount", "pieceType", "courseSectionId");
  }

  private static Set<String> toSet(JsonNode object) {
    return object.propertyNames().stream().collect(java.util.stream.Collectors.toSet());
  }
}
