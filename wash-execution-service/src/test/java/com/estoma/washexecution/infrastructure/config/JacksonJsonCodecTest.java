package com.estoma.washexecution.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.application.command.ActorContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JacksonJsonCodecTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final JacksonJsonCodec codec = new JacksonJsonCodec(mapper);

  @Test
  void systemActorUsesTheCanonicalWireShape() {
    var actor = mapper.readTree(codec.write(ActorContext.system("wash-execution")));

    assertThat(actor.path("type").asText()).isEqualTo("SYSTEM");
    assertThat(actor.path("service").asText()).isEqualTo("wash-execution");
    assertThat(actor.has("accountId")).isFalse();
    assertThat(actor.has("account")).isFalse();
  }

  @Test
  void accountActorUsesTheCanonicalWireShape() {
    UUID accountId = UUID.randomUUID();

    var actor = mapper.readTree(codec.write(ActorContext.account(accountId)));

    assertThat(actor.path("type").asText()).isEqualTo("ACCOUNT");
    assertThat(actor.path("accountId").asText()).isEqualTo(accountId.toString());
    assertThat(actor.has("service")).isFalse();
    assertThat(actor.has("account")).isFalse();
  }
}
