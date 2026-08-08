package com.estoma.authentication.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.testsupport.AbstractRabbitMqIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the documented operational HTTP surface (system_contract.md
 * "Operational HTTP Interface"): every health path and {@code
 * /metrics}/{@code /info} respond when the database and RabbitMQ are both
 * healthy. Extends {@link AbstractRabbitMqIT}, not just {@code
 * AbstractPostgresIT}: readiness now also reflects {@code
 * commandConsumer}/{@code outboxRelay} (the messaging tramo), so a real
 * broker must be present for {@code /health/readiness} to report UP.
 *
 * <p>{@code @AutoConfigureMetrics} is required: Spring Boot's test support
 * disables metrics export by default in every {@code @SpringBootTest} (so
 * tests never publish to a real monitoring backend), which means {@code
 * PrometheusMetricsExportAutoConfiguration} never creates its {@code
 * PrometheusMeterRegistry} bean and {@code /metrics} never registers at all
 * unless this annotation opts back in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureMetrics
class HealthEndpointsIT extends AbstractRabbitMqIT {

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
    assertThat(statusOf(path)).isEqualTo(200);
  }

  private int statusOf(String path) {
    return restTemplate.getForEntity(path, String.class).getStatusCode().value();
  }
}
