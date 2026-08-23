package com.estoma.authorization.application.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.application.command.ApplicationResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResultIntentFactoryTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void omitsInapplicableFieldsAndRepresentsErrorDetailsAsAnObject() {
    JsonNode payload = mapper.readTree(ResultIntentFactory.payload(
        ApplicationResult.rejected("TARGET_SCOPE_NOT_ALLOWED", "target is not eligible")));

    assertThat(payload.has("data")).isFalse();
    assertThat(payload.path("error").path("code").asText()).isEqualTo("TARGET_SCOPE_NOT_ALLOWED");
    assertThat(payload.path("error").path("details").isObject()).isTrue();
    assertThat(payload.path("error").path("details").path("message").asText())
        .isEqualTo("target is not eligible");
  }

  @Test
  void successPayloadOmitsError() {
    JsonNode payload = mapper.readTree(ResultIntentFactory.payload(
        ApplicationResult.succeeded(Map.of("changed", true))));

    assertThat(payload.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(payload.path("data").path("changed").asBoolean()).isTrue();
    assertThat(payload.has("error")).isFalse();
  }
}
