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

  @Test
  void absentAndExplicitNullEntryChecksNormalizeToSameUnclassifiedCommandAndFingerprint() {
    UUID executionId = UUID.randomUUID();
    UUID supervisorId = UUID.randomUUID();
    ObjectNode absent = entryPayload(executionId, supervisorId);
    ObjectNode explicitNull = absent.deepCopy();
    explicitNull.putNull("identityConfirmed");
    explicitNull.putNull("requirementsSatisfied");

    WashExecutionCommand absentCommand = mapper.map(entryEnvelope(supervisorId, absent)).command();
    WashExecutionCommand nullCommand = mapper.map(entryEnvelope(supervisorId, explicitNull)).command();

    assertThat(absentCommand).isEqualTo(nullCommand).isEqualTo(
        new WashExecutionCommand.DecideWashEntry(executionId, null, null,
            com.estoma.washexecution.domain.execution.EntryDecision.REJECTED,
            "Unclassified operational reason", supervisorId, ASSIGNED_AT, 1));
    var fingerprints = new JacksonJsonCodec(json);
    assertThat(fingerprints.fingerprint(Map.of("operation", "DecideWashEntry", "command", absentCommand)))
        .isEqualTo(fingerprints.fingerprint(
            Map.of("operation", "DecideWashEntry", "command", nullCommand)));
  }

  @Test
  void partialEntryChecksAreRejectedAndLegacyFingerprintIsUnchanged() {
    UUID executionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID supervisorId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    ObjectNode partial = entryPayload(executionId, supervisorId);
    partial.put("identityConfirmed", false);
    assertThatThrownBy(() -> mapper.map(entryEnvelope(supervisorId, partial)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("AMBIGUOUS_OR_INVALID_ENTRY_CHECKS");

    ObjectNode legacyPayload = entryPayload(executionId, supervisorId);
    legacyPayload.put("identityConfirmed", false);
    legacyPayload.put("requirementsSatisfied", false);
    WashExecutionCommand legacy = mapper.map(entryEnvelope(supervisorId, legacyPayload)).command();
    var primitiveShape = new LegacyDecideWashEntry(executionId, false, false, "REJECTED",
        "Unclassified operational reason", supervisorId, ASSIGNED_AT, 1);
    var fingerprints = new JacksonJsonCodec(json);
    String legacyFingerprint = fingerprints.fingerprint(
        Map.of("operation", "DecideWashEntry", "command", legacy));
    assertThat(legacyFingerprint)
        .isEqualTo(fingerprints.fingerprint(
            Map.of("operation", "DecideWashEntry", "command", primitiveShape)));
    assertThat(legacyFingerprint)
        .isEqualTo("8289aebb852581a1a7cf3ff7895af758b938d0d80b7409f3d9cfd538d0528cfb");
  }

  private ObjectNode entryPayload(UUID executionId, UUID supervisorId) {
    ObjectNode payload = json.createObjectNode();
    payload.put("washExecutionId", executionId.toString());
    payload.put("decision", "REJECTED");
    payload.put("rejectionReason", "Unclassified operational reason");
    payload.put("decidedByAccountId", supervisorId.toString());
    payload.put("decidedAt", ASSIGNED_AT.toString());
    payload.put("expectedVersion", 1);
    return payload;
  }

  private MessageEnvelope entryEnvelope(UUID supervisorId, ObjectNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), "DecideWashEntry", 1, MessageKind.COMMAND,
        "bff", ASSIGNED_AT, UUID.randomUUID(), null, UUID.randomUUID(),
        "entry:" + UUID.randomUUID(), null, null,
        new Actor("ACCOUNT", supervisorId, null), payload);
  }

  private record LegacyDecideWashEntry(
      UUID washExecutionId,
      boolean identityConfirmed,
      boolean requirementsSatisfied,
      String decision,
      String rejectionReason,
      UUID decidedByAccountId,
      Instant decidedAt,
      int expectedVersion) {}

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
