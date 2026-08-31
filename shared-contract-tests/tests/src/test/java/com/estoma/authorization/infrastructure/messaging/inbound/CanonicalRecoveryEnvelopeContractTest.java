package com.estoma.authorization.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the concrete recovery-query encoders against Authorization's strict inbound validator.
 * A producer may not add null-valued fields for another actor variant or optional query value.
 */
class CanonicalRecoveryEnvelopeContractTest {

  private static final String RECOVERY_QUERY = "ListAccountAuthorizationSnapshotsPage";

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final EnvelopeValidator validator = new EnvelopeValidator(
      new com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec(mapper));

  @Test
  void recoveryProducersEmitQueriesAcceptedByAuthorization() {
    assertAccepted(academicRecoveryQuery());
    assertAccepted(washExecutionRecoveryQuery());
    assertAccepted(washAdministrationRecoveryQuery());
  }

  private void assertAccepted(byte[] body) {
    JsonNode wire = mapper.readTree(body);

    assertThat(wire.path("actor").propertyNames()).containsExactlyInAnyOrder("type", "service");
    assertThat(wire.path("actor").path("type").asText()).isEqualTo("SYSTEM");
    assertThat(wire.path("actor").has("accountId")).isFalse();
    assertThat(wire.has("causationId")).isFalse();
    assertThat(wire.has("idempotencyKey")).isFalse();
    assertThat(wire.has("aggregateId")).isFalse();
    assertThat(wire.has("aggregateVersion")).isFalse();
    assertThat(wire.path("payload").has("cursor")).isFalse();
    assertThatCode(() -> validator.validate(wire, com.estoma.authorization.contracts.MessageKind.QUERY,
        Set.of(RECOVERY_QUERY))).doesNotThrowAnyException();
  }

  private byte[] academicRecoveryQuery() {
    return new com.estoma.academic.messaging.EnvelopeCodec(mapper).encode(
        new com.estoma.academic.contract.MessageEnvelope(
            UUID.randomUUID(), RECOVERY_QUERY, 1, "QUERY", "academic", occurredAt(), UUID.randomUUID(),
            null, UUID.randomUUID(), null, null, null,
            new com.estoma.academic.contract.Actor("SYSTEM", null, "academic"), recoveryPayload()));
  }

  private byte[] washExecutionRecoveryQuery() {
    return new com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec(mapper).encode(
        new com.estoma.washexecution.contracts.MessageEnvelope(
            UUID.randomUUID(), RECOVERY_QUERY, 1, com.estoma.washexecution.contracts.MessageKind.QUERY,
            "wash-execution", occurredAt(), UUID.randomUUID(), null, UUID.randomUUID(), null, null, null,
            new com.estoma.washexecution.contracts.Actor("SYSTEM", null, "wash-execution"), recoveryPayload()));
  }

  private byte[] washAdministrationRecoveryQuery() {
    return new com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec(mapper).encode(
        new com.estoma.washadministration.contracts.MessageEnvelope(
            UUID.randomUUID(), RECOVERY_QUERY, 1, com.estoma.washadministration.contracts.MessageKind.QUERY,
            "wash-administration", occurredAt(), UUID.randomUUID(), null, UUID.randomUUID(), null, null, null,
            new com.estoma.washadministration.contracts.Actor("SYSTEM", null, "wash-administration"),
            recoveryPayload()));
  }

  private JsonNode recoveryPayload() {
    return mapper
        .createObjectNode()
        .putNull("cursor")
        .put("limit", 100)
        .put("systemCode", "LAVADO_ULTRASONICO");
  }

  private static Instant occurredAt() {
    return Instant.parse("2026-08-30T00:00:00Z");
  }
}
