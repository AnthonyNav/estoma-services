package com.estoma.washadministration.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washadministration.testsupport.AbstractPostgresIT;
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
  void everyDocumentedOperationalEndpointRespondsSuccessfully() {
    assertOk("/health");
    assertOk("/health/startup");
    assertOk("/health/readiness");
    assertOk("/health/liveness");
    assertOk("/metrics");
    assertOk("/info");
  }

  private void assertOk(String path) {
    assertThat(restTemplate.getForEntity(path, String.class).getStatusCode().value()).isEqualTo(200);
  }
}
