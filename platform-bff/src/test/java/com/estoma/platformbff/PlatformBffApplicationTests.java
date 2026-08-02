package com.estoma.platformbff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PlatformBffApplicationTests {
  private final SystemController controller = new SystemController();
  @Test void contextLoads() {}
  @Test void healthReturnsOk() { assertThat(controller.health()).containsAllEntriesOf(Map.of("status", "ok", "service", "platform-bff")); }
  @Test void versionReturnsServiceVersion() { assertThat(controller.version()).containsAllEntriesOf(Map.of("service", "platform-bff", "version", "0.1.0")); }
}
