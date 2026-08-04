package com.estoma.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuthenticationApplicationTests {
  private final SystemController controller = new SystemController();

  @Test void contextLoads() {}

  @Test
  void healthReturnsOk() {
    assertThat(controller.health())
        .containsAllEntriesOf(Map.of("status", "ok", "service", "authentication-service"));
  }

  @Test
  void startupReturnsOk() {
    assertThat(controller.startup())
        .containsAllEntriesOf(Map.of("status", "ok", "service", "authentication-service"));
  }

  @Test
  void readinessReturnsOk() {
    assertThat(controller.readiness())
        .containsAllEntriesOf(Map.of("status", "ok", "service", "authentication-service"));
  }

  @Test
  void livenessReturnsOk() {
    assertThat(controller.liveness())
        .containsAllEntriesOf(Map.of("status", "ok", "service", "authentication-service"));
  }

  @Test
  void versionReturnsServiceVersion() {
    assertThat(controller.version())
        .containsAllEntriesOf(Map.of("service", "authentication-service", "version", "0.1.0"));
  }
}
