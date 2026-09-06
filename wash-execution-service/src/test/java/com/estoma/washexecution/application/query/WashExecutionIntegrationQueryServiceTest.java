package com.estoma.washexecution.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.port.JsonCodec;
import com.estoma.washexecution.application.port.OutboxRepository;
import com.estoma.washexecution.application.port.WashExecutionIntegrationQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WashExecutionIntegrationQueryServiceTest {
  @Test
  void entitySnapshotCarriesOwnerGenerationTimestamp() throws Exception {
    Instant snapshotAt = Instant.parse("2026-08-23T18:30:00Z");
    UUID appointmentId = UUID.randomUUID();
    AtomicReference<OutboxRepository.OutboxMessage> published = new AtomicReference<>();
    ObjectMapper mapper = new ObjectMapper();

    WashExecutionIntegrationQueryRepository repository = new WashExecutionIntegrationQueryRepository() {
      @Override
      public EntityContext get(UUID requestedAppointmentId) {
        return new EntityContext(requestedAppointmentId, null, List.of(), List.of());
      }

      @Override
      public Page list(java.time.LocalDate serviceDate, int pageSize, String pageToken) {
        throw new UnsupportedOperationException();
      }
    };
    JsonCodec json = new JsonCodec() {
      @Override
      public String write(Object value) {
        try {
          return mapper.writeValueAsString(value);
        } catch (Exception exception) {
          throw new IllegalStateException(exception);
        }
      }

      @Override
      public String fingerprint(Object value) {
        throw new UnsupportedOperationException();
      }
    };
    WashExecutionIntegrationQueryService service = new WashExecutionIntegrationQueryService(
        repository, null, null, null, null, null,
        published::set, json, Clock.fixed(snapshotAt, ZoneOffset.UTC));
    IntegrationQueryMetadata metadata = new IntegrationQueryMetadata(
        UUID.randomUUID(), "GetWashExecutionIntegrationContext", "appointments",
        UUID.randomUUID(), null, ActorContext.system("appointments"));

    service.respond(metadata, new WashExecutionIntegrationQuery.GetContext(appointmentId));

    OutboxRepository.OutboxMessage result = published.get();
    var payload = mapper.readTree(result.payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(payload.path("data").path("appointmentId").asString()).isEqualTo(appointmentId.toString());
    assertThat(payload.path("data").path("snapshotGeneratedAt").asString()).isEqualTo(snapshotAt.toString());
    assertThat(result.occurredAt()).isEqualTo(snapshotAt);
  }
}
