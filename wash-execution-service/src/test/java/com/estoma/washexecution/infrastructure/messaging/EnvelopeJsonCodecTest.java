package com.estoma.washexecution.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeJsonCodecTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(mapper);

  @Test
  void encodesAuthorizationRecoveryQueryWithTheCanonicalSystemActor() {
    UUID operationId = UUID.randomUUID();
    JsonNode encoded = mapper.readTree(codec.encode(new MessageEnvelope(
        UUID.randomUUID(),
        "ListAccountAuthorizationSnapshotsPage",
        1,
        MessageKind.QUERY,
        "wash-execution",
        Instant.parse("2026-08-30T00:00:00Z"),
        UUID.randomUUID(),
        null,
        operationId,
        null,
        null,
        null,
        new Actor("SYSTEM", null, "wash-execution"),
        mapper.readTree("{\"limit\":200,\"systemCode\":\"LAVADO_ULTRASONICO\"}"))));

    assertThat(encoded.path("messageKind").asText()).isEqualTo("QUERY");
    assertThat(encoded.path("operationId").asText()).isEqualTo(operationId.toString());
    assertThat(encoded.has("causationId")).isFalse();
    assertThat(encoded.has("idempotencyKey")).isFalse();
    assertThat(encoded.has("aggregateId")).isFalse();
    assertThat(encoded.has("aggregateVersion")).isFalse();
    assertThat(encoded.path("actor").propertyNames()).containsExactlyInAnyOrder("type", "service");
    assertThat(encoded.path("actor").path("type").asText()).isEqualTo("SYSTEM");
    assertThat(encoded.path("actor").path("service").asText()).isEqualTo("wash-execution");
    assertThat(encoded.path("actor").has("accountId")).isFalse();
    assertThat(encoded.path("actor").has("account")).isFalse();
    assertThat(encoded.toString()).doesNotContain(":null");
  }

  @Test
  void encodesAccountEnvelopeWithTheCanonicalAccountActor() {
    UUID accountId = UUID.randomUUID();
    JsonNode encoded = mapper.readTree(codec.encode(new MessageEnvelope(
        UUID.randomUUID(),
        "ExampleAccountEvent",
        1,
        MessageKind.EVENT,
        "wash-execution",
        Instant.parse("2026-08-30T00:00:00Z"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        UUID.randomUUID(),
        1,
        new Actor("ACCOUNT", accountId, "must-not-be-published"),
        mapper.readTree("{\"optional\":null,\"kept\":true}"))));

    assertThat(encoded.path("actor").propertyNames()).containsExactlyInAnyOrder("type", "accountId");
    assertThat(encoded.path("actor").path("type").asText()).isEqualTo("ACCOUNT");
    assertThat(encoded.path("actor").path("accountId").asText()).isEqualTo(accountId.toString());
    assertThat(encoded.path("actor").has("service")).isFalse();
    assertThat(encoded.path("actor").has("account")).isFalse();
    assertThat(encoded.path("payload").has("optional")).isFalse();
    assertThat(encoded.path("payload").path("kept").asBoolean()).isTrue();
    assertThat(encoded.toString()).doesNotContain(":null");
  }
}
