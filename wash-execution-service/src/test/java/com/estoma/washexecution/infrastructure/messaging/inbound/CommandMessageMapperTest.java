package com.estoma.washexecution.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washexecution.application.command.WashExecutionCommand;
import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.infrastructure.config.JacksonJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class CommandMessageMapperTest {
  private static final Instant ASSIGNED_AT = Instant.parse("2026-09-06T16:00:00Z");
  private final ObjectMapper json = new ObjectMapper();
  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(json);
  private final CommandMessageMapper mapper = new CommandMessageMapper(codec);

  @Test
  void canonicalAndLegacyShapesNormalizeToTheSameCommandAndFingerprint() {
    UUID executionId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID tankId = UUID.randomUUID();
    UUID supervisorId = UUID.randomUUID();

    WashExecutionCommand canonical = mapper.map(envelope(supervisorId,
        payload(executionId, cabinId, tankId, supervisorId, true))).command();
    WashExecutionCommand legacy = mapper.map(envelope(supervisorId,
        payload(executionId, cabinId, tankId, supervisorId, false))).command();

    assertThat(canonical).isEqualTo(legacy).isEqualTo(
        new WashExecutionCommand.ResolvePendingReassignment(
            executionId, cabinId, tankId, supervisorId, ASSIGNED_AT, 2));
    var fingerprints = new JacksonJsonCodec(json);
    assertThat(fingerprints.fingerprint(Map.of("operation", "ResolvePendingReassignment",
            "command", canonical)))
        .isEqualTo(fingerprints.fingerprint(Map.of("operation", "ResolvePendingReassignment",
            "command", legacy)));
  }

  @Test
  void mixedOrPartialAliasFamiliesAreRejectedBeforeMapping() {
    UUID supervisorId = UUID.randomUUID();
    ObjectNode mixed = payload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        supervisorId, true);
    mixed.put("cabinId", UUID.randomUUID().toString());
    assertThatThrownBy(() -> mapper.map(envelope(supervisorId, mixed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("AMBIGUOUS_OR_INVALID_REASSIGNMENT_PAYLOAD");

    ObjectNode partial = payload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        supervisorId, true);
    partial.remove("assignedAt");
    assertThatThrownBy(() -> mapper.map(envelope(supervisorId, partial)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("AMBIGUOUS_OR_INVALID_REASSIGNMENT_PAYLOAD");
  }

  private ObjectNode payload(UUID executionId, UUID cabinId, UUID tankId,
      UUID supervisorId, boolean canonical) {
    ObjectNode payload = json.createObjectNode();
    payload.put("washExecutionId", executionId.toString());
    payload.put(canonical ? "selectedCabinId" : "cabinId", cabinId.toString());
    payload.put(canonical ? "selectedTankId" : "tankId", tankId.toString());
    payload.put(canonical ? "assignedByAccountId" : "resolvedByAccountId",
        supervisorId.toString());
    payload.put(canonical ? "assignedAt" : "resolvedAt", ASSIGNED_AT.toString());
    payload.put("expectedVersion", 2);
    return payload;
  }

  private MessageEnvelope envelope(UUID supervisorId, ObjectNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), "ResolvePendingReassignment", 1, MessageKind.COMMAND,
        "bff", ASSIGNED_AT, UUID.randomUUID(), null, UUID.randomUUID(),
        "resolve:" + UUID.randomUUID(), null, null,
        new Actor("ACCOUNT", supervisorId, null), payload);
  }
}
