package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.appointments.application.port.in.ScheduleAppointmentUseCase;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.infrastructure.security.AppointmentQrTokenService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MessagingContractTest {
  @Test
  void envelopeRoundTripKeepsRequiredIdentityAndPayload() {
    UUID messageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    EnvelopeJsonCodec codec = new EnvelopeJsonCodec(new ObjectMapper());
    MessageEnvelope input = new MessageEnvelope(messageId, "ScheduleAppointment", 1, MessageKind.COMMAND,
        "bff", Instant.parse("2026-01-01T10:00:00Z"), UUID.randomUUID(), null, operationId, "key-1", null, null,
        new WireActor("ACCOUNT", UUID.randomUUID(), null), codec.readTree("{\"ok\":true}"));
    MessageEnvelope output = codec.decode(codec.readTree(codec.encode(input)));
    assertThat(output.messageId()).isEqualTo(messageId);
    assertThat(output.messageKind()).isEqualTo(MessageKind.COMMAND);
    assertThat(output.operationId()).isEqualTo(operationId);
    assertThat(output.actor().type()).isEqualTo("ACCOUNT");
    assertThat(output.payload().path("ok").asBoolean()).isTrue();
  }

  @Test
  void acceptsExplicitNullForOptionalAggregateVersionFromAnotherService() {
    UUID messageId = UUID.randomUUID();
    EnvelopeJsonCodec codec = new EnvelopeJsonCodec(new ObjectMapper());
    String wire = """
        {"messageId":"%s","messageType":"GetWashExecutionIntegrationContextResult",
        "messageVersion":1,"messageKind":"RESULT","producer":"wash-execution",
        "occurredAt":"2026-01-01T10:00:00Z","correlationId":"%s",
        "aggregateVersion":null,"actor":{"type":"SYSTEM","service":"appointments"},"payload":{}}
        """.formatted(messageId, UUID.randomUUID());

    MessageEnvelope decoded = codec.decode(codec.readTree(wire));

    assertThat(decoded.aggregateVersion()).isNull();
  }

  @Test
  void scheduleAppointmentWireRequiresExplicitRegulationAcceptance() {
    EnvelopeJsonCodec codec = new EnvelopeJsonCodec(new ObjectMapper());
    CommandMessageMapper commandMapper = new CommandMessageMapper();
    UUID studentId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    var payload = new ObjectMapper().createObjectNode()
        .put("appointmentId", appointmentId.toString())
        .put("studentAccountId", studentId.toString())
        .put("appointmentTimeSlotId", UUID.randomUUID().toString())
        .put("courseSectionId", UUID.randomUUID().toString())
        .put("appointmentType", "NORMAL")
        .put("instrumentCount", 1)
        .put("pieceType", "HIGH_SPEED")
        .put("regulationAccepted", true);
    MessageEnvelope envelope = new MessageEnvelope(UUID.randomUUID(), "ScheduleAppointment", 1,
        MessageKind.COMMAND, "bff", Instant.parse("2026-01-01T10:00:00Z"), UUID.randomUUID(), null,
        UUID.randomUUID(), "key-1", appointmentId, null,
        new WireActor("ACCOUNT", studentId, null), payload);

    var mapped = commandMapper.map(envelope);
    assertThat(((ScheduleAppointmentUseCase.Command) mapped.command()).regulationAccepted()).isTrue();

    payload.remove("regulationAccepted");
    assertThatThrownBy(() -> commandMapper.map(new MessageEnvelope(envelope.messageId(),
        envelope.messageType(), envelope.messageVersion(), envelope.messageKind(), envelope.producer(),
        envelope.occurredAt(), envelope.correlationId(), envelope.causationId(), envelope.operationId(),
        envelope.idempotencyKey(), envelope.aggregateId(), envelope.aggregateVersion(), envelope.actor(), payload)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void validatorRejectsObsoleteOrWrongKindMessagesBeforeMapping() {
    EnvelopeJsonCodec codec = new EnvelopeJsonCodec(new ObjectMapper());
    EnvelopeValidator validator = new EnvelopeValidator(codec);
    MessageEnvelope input = new MessageEnvelope(UUID.randomUUID(), "AcademicPeriodRegistered", 1, MessageKind.EVENT,
        "academic", Instant.now(), UUID.randomUUID(), null, null, null, null, null,
        new WireActor("SYSTEM", null, "academic"), codec.readTree("{}"));
    assertThatThrownBy(() -> validator.validate(codec.readTree(codec.encode(input)), MessageKind.EVENT,
        Set.of("AcademicPeriodActivated"))).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void qrTamperingIsRejectedAndSignatureDoesNotDecideEntry() {
    UUID appointmentId = UUID.randomUUID();
    AppointmentQrTokenService qr = new AppointmentQrTokenService(
        "0123456789abcdef0123456789abcdef");
    String token = qr.issue(appointmentId);
    assertThat(qr.verify(token)).isEqualTo(appointmentId);
    assertThatThrownBy(() -> qr.verify(token.replace(appointmentId.toString(), UUID.randomUUID().toString())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
