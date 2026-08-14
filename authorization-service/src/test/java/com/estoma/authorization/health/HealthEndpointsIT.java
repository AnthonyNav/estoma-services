package com.estoma.authorization.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureMetrics
class HealthEndpointsIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void documentedOperationalEndpointsRespondSuccessfully() {
    assertOk("/actuator/health");
    assertOk("/actuator/health/liveness");
    assertOk("/actuator/health/readiness");
    assertOk("/actuator/prometheus");
  }

  @Test
  void startupHealthGroupIsNotExposed() {
    assertThat(restTemplate.getForEntity("/actuator/health/startup", String.class).getStatusCode().value())
        .isEqualTo(403);
  }

  private void assertOk(String path) {
    assertThat(restTemplate.getForEntity(path, String.class).getStatusCode().value()).isEqualTo(200);
  }
}
