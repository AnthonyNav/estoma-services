package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.contracts.messaging.ActorContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ActorContextWireTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void accountActorUsesCanonicalAccountIdField() {
    UUID accountId = UUID.randomUUID();
    JsonNode json = mapper.readTree(
        mapper.writeValueAsString(ActorContext.account(accountId, "GLOBAL", null)));

    assertThat(json.path("type").asText()).isEqualTo("ACCOUNT");
    assertThat(json.path("accountId").asText()).isEqualTo(accountId.toString());
    assertThat(json.path("scope").asText()).isEqualTo("GLOBAL");
    assertThat(json.has("id")).isFalse();
  }

  @Test
  void anonymousActorMatchesAuthenticationPublicContract() {
    JsonNode json = mapper.readTree(mapper.writeValueAsString(ActorContext.anonymous()));

    assertThat(json.path("type").asText()).isEqualTo("ANONYMOUS");
    assertThat(json.path("scope").asText()).isEqualTo("PUBLIC");
    assertThat(json.hasNonNull("accountId")).isFalse();
    assertThat(json.hasNonNull("service")).isFalse();
    assertThat(json.hasNonNull("systemId")).isFalse();
  }
}
