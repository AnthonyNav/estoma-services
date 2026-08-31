package com.estoma.washadministration.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessageKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeJsonCodecTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(mapper);

  @Test
  void systemQueryUsesTheCanonicalAuthorizationWireShape() {
    JsonNode wire = mapper.readTree(codec.encode(systemQuery()));

    assertThat(wire.has("causationId")).isFalse();
    assertThat(wire.has("idempotencyKey")).isFalse();
    assertThat(wire.has("aggregateId")).isFalse();
    assertThat(wire.has("aggregateVersion")).isFalse();
    assertThat(wire.path("actor").propertyNames())
        .containsExactlyInAnyOrder("type", "service");
    assertThat(wire.path("actor").path("type").asText()).isEqualTo("SYSTEM");
    assertThat(wire.path("actor").path("service").asText()).isEqualTo("wash-administration");
    assertThat(wire.path("actor").has("accountId")).isFalse();
  }

  @Test
  void accountActorDoesNotExposeSystemFields() {
    UUID accountId = UUID.randomUUID();
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), "GetAccountAuthorizationSnapshot", 1, MessageKind.QUERY,
        "wash-administration", Instant.parse("2026-08-30T00:00:00Z"), UUID.randomUUID(), null,
        UUID.randomUUID(), null, null, null, new Actor("ACCOUNT", accountId, "wash-administration"),
        mapper.createObjectNode());

    JsonNode wire = mapper.readTree(codec.encode(envelope));

    assertThat(wire.path("actor").propertyNames())
        .containsExactlyInAnyOrder("type", "accountId");
    assertThat(wire.path("actor").path("accountId").asText()).isEqualTo(accountId.toString());
    assertThat(wire.path("actor").has("service")).isFalse();
  }

  private MessageEnvelope systemQuery() {
    return new MessageEnvelope(
        UUID.randomUUID(), "GetAccountAuthorizationSnapshot", 1, MessageKind.QUERY,
        "wash-administration", Instant.parse("2026-08-30T00:00:00Z"), UUID.randomUUID(), null,
        UUID.randomUUID(), null, null, null, Actor.system("wash-administration"),
        mapper.createObjectNode());
  }
}
