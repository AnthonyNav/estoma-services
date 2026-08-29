package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.appointments.contracts.MessageKind;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class Pr3ContractHardeningTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void routesEachDurableMessageKindToItsCanonicalDestination() {
    assertThat(OutboxRelay.routingKey(MessageKind.COMMAND, "MarkAppointmentAsMissed"))
        .isEqualTo("appointments");
    assertThat(OutboxRelay.routingKey(MessageKind.QUERY, "GetAppointmentAcademicConfiguration"))
        .isEqualTo("academic");
    assertThat(OutboxRelay.routingKey(MessageKind.QUERY, "ListAccountAuthorizationSnapshotsPage"))
        .isEqualTo("authorization");
    assertThat(OutboxRelay.routingKey(MessageKind.QUERY, "GetWashAdministrationConfiguration"))
        .isEqualTo("wash-administration");
    assertThat(OutboxRelay.routingKey(MessageKind.QUERY, "ListWashExecutionIntegrationContextsPage"))
        .isEqualTo("wash-execution");
    assertThat(OutboxRelay.routingKey(MessageKind.QUERY, "GetWashExecutionIntegrationContext"))
        .isEqualTo("wash-execution");
    assertThat(OutboxRelay.routingKey(MessageKind.RESULT, "ScheduleAppointmentSucceeded"))
        .isEqualTo("bff");
    assertThat(OutboxRelay.routingKey(MessageKind.RESULT, "GetAppointmentExecutionContextResult"))
        .isEqualTo("wash-execution");
  }

  @Test
  void failsClosedForUnknownResultOrRecoveryQueryDestination() {
    assertThatThrownBy(() -> OutboxRelay.routingKey(MessageKind.RESULT, "UnknownResult"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OutboxRelay.routingKey(MessageKind.QUERY, "UnknownQuery"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void derivesCanonicalInternalCommandIdempotencyKeys() {
    var recalc = mapper.createObjectNode()
        .put("sourceEventId", UUID.randomUUID().toString())
        .put("slotId", UUID.randomUUID().toString())
        .put("expectedProjectionVersion", 3);
    assertThat(OutboxRelay.commandIdempotencyKey("RecalculateAppointmentCapacityForTimeSlot",
        recalc, UUID.randomUUID()))
        .isEqualTo(recalc.path("sourceEventId").asText() + ":" + recalc.path("slotId").asText());

    var missed = mapper.createObjectNode()
        .put("appointmentId", UUID.randomUUID().toString())
        .put("expectedVersion", 7);
    assertThat(OutboxRelay.commandIdempotencyKey("MarkAppointmentAsMissed", missed, UUID.randomUUID()))
        .isEqualTo(missed.path("appointmentId").asText() + ":7");
  }

  @Test
  void omitsZeroExpectedProjectionVersionFromInternalCommandEnvelope() {
    assertThat(OutboxRelay.envelopeAggregateVersion(MessageKind.COMMAND, 0L)).isNull();
    assertThat(OutboxRelay.envelopeAggregateVersion(MessageKind.EVENT, 0L)).isZero();
  }

  @Test
  void executionContextPageTokenIsOpaqueAndRoundTripsAppointmentId() {
    UUID id = UUID.randomUUID();
    String token = AppointmentExecutionQueryService.encodeToken(id);
    assertThat(token).doesNotContain(id.toString());
    assertThat(AppointmentExecutionQueryService.decodeToken(token)).isEqualTo(id);
    assertThatThrownBy(() -> AppointmentExecutionQueryService.decodeToken(
        Base64.getUrlEncoder().withoutPadding().encodeToString("not-a-uuid".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void externalEventProducerNamespaceIsFailClosed() {
    assertThat(ExternalEventListener.expectedProducer("StudentSemesterChanged")).isEqualTo("academic");
    assertThat(ExternalEventListener.expectedProducer("SystemAccessRevoked")).isEqualTo("authorization");
    assertThat(ExternalEventListener.expectedProducer("TankUpdated")).isEqualTo("wash-administration");
    assertThat(ExternalEventListener.expectedProducer("WashExecutionCompleted")).isEqualTo("wash-execution");
    assertThatThrownBy(() -> ExternalEventListener.expectedProducer("UnknownEvent"))
        .isInstanceOf(InvalidMessageException.class);
  }
}
