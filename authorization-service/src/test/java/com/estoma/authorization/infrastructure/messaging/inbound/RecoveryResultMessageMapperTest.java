package com.estoma.authorization.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RecoveryResultMessageMapperTest {

  @Test
  void rejectsIdempotencyKeyOnARecoveryResult() {
    var payload = JsonMapper.builder().build().createObjectNode().put("status", "NOT_FOUND");
    MessageEnvelope result = new MessageEnvelope(
        UUID.randomUUID(),
        "GetPersonStatusSnapshotResult",
        1,
        MessageKind.RESULT,
        "users",
        Instant.parse("2026-08-16T00:00:00Z"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new WireActor("SYSTEM", null, "users"),
        payload);

    assertThatThrownBy(() -> new RecoveryResultMessageMapper().map(result))
        .isInstanceOf(InvalidMessageException.class)
        .hasMessage("idempotencyKey is not applicable to recovery Results");
  }
}
