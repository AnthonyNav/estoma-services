package com.estoma.infratest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = "API_KEY=test-api-key")
class InfraTestApplicationTests {

  private final SystemController controller = new SystemController("test-api-key");

  @Test
  void contextLoads() {}

  @Test
  void healthReturnsOk() {
    assertThat(controller.health()).containsAllEntriesOf(Map.of("status", "ok", "service", "infra-test"));
  }

  @Test
  void versionRequiresApiKey() {
    assertThatThrownBy(() -> controller.version(null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void versionRejectsInvalidApiKey() {
    assertThatThrownBy(() -> controller.version("wrong-key"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void versionReturnsServiceVersion() {
    assertThat(controller.version("test-api-key"))
        .containsAllEntriesOf(Map.of("service", "infra-test", "version", "0.2.0"));
  }
}
