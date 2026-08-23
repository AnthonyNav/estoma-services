package com.estoma.authorization.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeJsonCodecTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(mapper);

  @Test
  void omitsNullFieldsAtEveryObjectDepth() {
    var payload = mapper.createObjectNode();
    payload.putNull("notApplicable");
    payload.putObject("nested").putNull("alsoNotApplicable");
    payload.putArray("items").addObject().putNull("notApplicable").put("value", "kept");

    JsonNode encoded = codec.readTree(codec.encode(envelope(payload)));

    assertThat(encoded.path("payload").has("notApplicable")).isFalse();
    assertThat(encoded.path("payload").path("nested").has("alsoNotApplicable")).isFalse();
    assertThat(encoded.path("payload").path("items").get(0).has("notApplicable")).isFalse();
    assertThat(encoded.path("payload").path("items").get(0).path("value").asText()).isEqualTo("kept");
  }

  @Test
  void rejectsNullArrayItemsInsteadOfChangingArraySemantics() {
    var payload = mapper.createObjectNode();
    payload.putArray("items").addNull();

    assertThatThrownBy(() -> codec.encode(envelope(payload)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("payload arrays must not contain null values");
  }

  private static MessageEnvelope envelope(JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        "AssignAccountRoleResult",
        1,
        MessageKind.RESULT,
        "authorization",
        Instant.parse("2026-08-16T00:00:00Z"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new WireActor("SYSTEM", null, "authorization"),
        payload);
  }
}
