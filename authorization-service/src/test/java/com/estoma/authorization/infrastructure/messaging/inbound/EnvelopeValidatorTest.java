package com.estoma.authorization.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeValidatorTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final EnvelopeValidator validator = new EnvelopeValidator(codec);

  @Test
  void acceptsOptionalIdempotencyKeyOnCommandResults() {
    var payload = JsonMapper.builder().build().createObjectNode().put("status", "SUCCEEDED");
    MessageEnvelope result = new MessageEnvelope(
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

    assertThatCode(() -> validator.validate(
        codec.readTree(codec.encode(result)), MessageKind.RESULT, Set.of("AssignAccountRoleResult")))
        .doesNotThrowAnyException();
  }
}
