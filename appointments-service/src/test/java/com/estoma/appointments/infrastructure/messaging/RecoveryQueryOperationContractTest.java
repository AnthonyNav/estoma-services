package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.infrastructure.observability.AppointmentOperationalMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RecoveryQueryOperationContractTest {
  private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"GetAppointmentAcademicConfiguration",
      "ListStudentAppointmentAcademicSnapshotsPage", "ListAccountAuthorizationSnapshotsPage",
      "GetWashAdministrationConfiguration", "GetWashExecutionIntegrationContext",
      "ListWashExecutionIntegrationContextsPage"})
  void publishesLegacyRecoveryRowsWithStableRequiredOperationId(String type) {
    UUID messageId = UUID.randomUUID();
    UUID generation = UUID.randomUUID();
    var row = new OutboxRepository.OutboxMessage(messageId, type, 1, "QUERY", null, null,
        generation, null, "{}", NOW, NOW, "PENDING", 0, null, null);
    var first = publish(row);
    var retry = publish(row);
    assertThat(first.path("operationId").asText()).isEqualTo(messageId.toString());
    assertThat(first.path("messageId").asText()).isEqualTo(messageId.toString());
    assertThat(first.path("correlationId").asText()).isEqualTo(generation.toString());
    assertThat(first.path("occurredAt").asText()).isEqualTo(NOW.toString());
    assertThat(first.has("idempotencyKey")).isFalse();
    assertThat(first.path("actor").has("accountId")).isFalse();
    assertThat(retry).isEqualTo(first);
  }

  @Test
  void preservesAnExplicitQueryOperationId() {
    UUID operation = UUID.randomUUID();
    var row = new OutboxRepository.OutboxMessage(UUID.randomUUID(),
        "GetWashExecutionIntegrationContext", 1, "QUERY", null, null,
        UUID.randomUUID(), null, "{}", NOW, NOW, "PENDING", 0, null, null, operation);
    assertThat(publish(row).path("operationId").asText()).isEqualTo(operation.toString());
  }

  @Test
  void doesNotInventOperationIdForInternalCommands() {
    var row = new OutboxRepository.OutboxMessage(UUID.randomUUID(), "MarkAppointmentAsMissed",
        1, "COMMAND", null, null, UUID.randomUUID(), null,
        "{\"appointmentId\":\"" + UUID.randomUUID() + "\",\"expectedVersion\":1}",
        NOW, NOW, "PENDING", 0, null, null);
    assertThat(publish(row).has("operationId")).isFalse();
  }

  private JsonNode publish(OutboxRepository.OutboxMessage row) {
    var outbox = mock(OutboxRepository.class);
    when(outbox.claimBatch(anyInt(), anyString(), any(), any())).thenReturn(List.of(row));
    when(outbox.markPublished(eq(row.messageId()), anyString(), any())).thenReturn(true);
    var publisher = mock(ConfirmedRabbitPublisher.class);
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var properties = new MessagingProperties(false, false, null, 1, null, null,
        true, false, null, null);
    var relay = new OutboxRelay(outbox, publisher, new EnvelopeJsonCodec(mapper), properties,
        transactions, Clock.fixed(NOW, ZoneOffset.UTC), mock(AppointmentOperationalMetrics.class));
    relay.relay();
    var wire = ArgumentCaptor.forClass(Message.class);
    verify(publisher).publishRequiredRoute(anyString(), anyString(), wire.capture());
    verify(outbox).markPublished(eq(row.messageId()), anyString(), any());
    return mapper.readTree(wire.getValue().getBody());
  }
}
