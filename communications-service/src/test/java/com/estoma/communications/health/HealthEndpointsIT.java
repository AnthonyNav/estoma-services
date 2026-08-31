package com.estoma.communications.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the documented operational HTTP surface (system_contract.md
 * "Operational HTTP Interface"): every health path and {@code
 * /metrics}/{@code /info} respond when the database is healthy. Messaging
 * infrastructure is deliberately disabled by {@link AbstractPostgresIT}, so
 * this HTTP-surface test disables only RabbitMQ's connection health check.
 *
 * <p>{@code @AutoConfigureMetrics} is required: Spring Boot's test support
 * disables metrics export by default in every {@code @SpringBootTest} (so
 * tests never publish to a real monitoring backend), which means {@code
 * PrometheusMetricsExportAutoConfiguration} never creates its {@code
 * PrometheusMeterRegistry} bean and {@code /metrics} never registers at all
 * unless this annotation opts back in.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.health.rabbit.enabled=false")
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
    assertThat(statusOf(path)).isEqualTo(200);
  }

  private int statusOf(String path) {
    return restTemplate.getForEntity(path, String.class).getStatusCode().value();
  }
}
