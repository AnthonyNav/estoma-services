package com.estoma.washexecution.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.testsupport.AbstractMessagingIT;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

class CommandWireValidationIT extends AbstractMessagingIT {
  private static final Instant NOW = Instant.parse("2026-08-23T10:00:00Z");

  @Autowired EnvelopeJsonCodec codec;

  @Test
  void missingPrimitiveExpectedVersionIsWireInvalidInsteadOfBecomingVersionConflict() {
    UUID actor = UUID.randomUUID();
    MessageEnvelope command = envelope(
        "DecideWashEntry",
        actor,
        Map.of(
            "washExecutionId", UUID.randomUUID(),
            "identityConfirmed", true,
            "requirementsSatisfied", true,
            "decision", "AUTHORIZED",
            "decidedByAccountId", actor,
            "decidedAt", NOW));

    send(command);

    assertThat(rabbit.receive(MessagingNames.COMMAND_DLQ, 5_000)).isNotNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages", Integer.class)).isZero();
  }

  @Test
  void missingExitMaterialCountIsWireInvalidInsteadOfDefaultingToZero() {
    UUID student = UUID.randomUUID();
    MessageEnvelope command = envelope(
        "SubmitWashExit",
        student,
        Map.of(
            "washExecutionId", UUID.randomUUID(),
            "submittedByStudentAccountId", student,
            "materials", Map.of(
                "packageCount", 1,
                "greenPaperCassette8Count", 0,
                "greenPaperCassette10Count", 0),
            "submittedAt", NOW,
            "expectedVersion", 3));

    send(command);

    assertThat(rabbit.receive(MessagingNames.COMMAND_DLQ, 5_000)).isNotNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class)).isZero();
  }

  @Test
  void mixedCanonicalAndLegacyReassignmentAliasesGoToDlqWithoutInboxOrOutbox() {
    UUID supervisor = UUID.randomUUID();
    MessageEnvelope command = envelope(
        "ResolvePendingReassignment",
        supervisor,
        Map.of(
            "washExecutionId", UUID.randomUUID(),
            "selectedCabinId", UUID.randomUUID(),
            "selectedTankId", UUID.randomUUID(),
            "assignedByAccountId", supervisor,
            "assignedAt", NOW,
            "cabinId", UUID.randomUUID(),
            "tankId", UUID.randomUUID(),
            "resolvedByAccountId", supervisor,
            "resolvedAt", NOW,
            "expectedVersion", 2));

    send(command);

    assertThat(rabbit.receive(MessagingNames.COMMAND_DLQ, 5_000)).isNotNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages", Integer.class)).isZero();
  }

  private MessageEnvelope envelope(String type, UUID actorId, Map<String, Object> payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.COMMAND, "bff", NOW,
        UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID().toString(), null, null,
        new Actor("ACCOUNT", actorId, null), codec.toNode(payload));
  }

  private void send(MessageEnvelope envelope) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(envelope.messageId().toString());
    rabbit.send(
        MessagingNames.COMMANDS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID,
        new Message(codec.encode(envelope), props));
  }
}
