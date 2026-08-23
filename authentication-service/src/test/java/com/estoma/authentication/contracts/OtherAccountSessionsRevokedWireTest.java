package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.event.OtherAccountSessionsRevokedPayload;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OtherAccountSessionsRevokedWireTest {
  @Test
  void usesCanonicalCurrentSessionIdField() {
    UUID accountId = UUID.randomUUID();
    UUID currentSessionId = UUID.randomUUID();
    var payload = new OtherAccountSessionsRevokedPayload(
        accountId, currentSessionId, "PASSWORD_CHANGED", Instant.parse("2026-08-23T12:00:00Z"));
    var json = JsonMapper.builder().findAndAddModules().build().valueToTree(payload);

    assertThat(json.path("currentSessionId").asText()).isEqualTo(currentSessionId.toString());
    assertThat(json.has("retainedSessionId")).isFalse();
  }
}
