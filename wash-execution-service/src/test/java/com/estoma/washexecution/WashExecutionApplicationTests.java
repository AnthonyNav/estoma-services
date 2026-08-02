package com.estoma.washexecution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WashExecutionApplicationTests {
  private final SystemController controller = new SystemController();
  @Test void contextLoads() {}
  @Test void healthReturnsOk() { assertThat(controller.health()).containsAllEntriesOf(Map.of("status", "ok", "service", "wash-execution-service")); }
  @Test void versionReturnsServiceVersion() { assertThat(controller.version()).containsAllEntriesOf(Map.of("service", "wash-execution-service", "version", "0.1.0")); }
}
