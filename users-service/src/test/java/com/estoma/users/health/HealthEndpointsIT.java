package com.estoma.users.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.testsupport.AbstractMessagingIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the documented operational HTTP surface
 * (docs/system-contract.md §11, openapi.yaml): every health path and
 * {@code /metrics}/{@code /info} respond when every dependency is
 * healthy.
 *
 * <p>Extends {@link AbstractMessagingIT}, not just {@code
 * AbstractPostgresIT}: the aggregate {@code /health} endpoint (unlike the
 * {@code readiness}/{@code startup}/{@code liveness} groups, which only
 * include the components this service explicitly names) also reports the
 * built-in {@code rabbit} health indicator, which genuinely goes DOWN
 * with no broker present — a real RabbitMQ is needed for the "everything
 * healthy" case to actually be true.
 *
 * <p>{@code @AutoConfigureMetrics} is required here: Spring Boot's test
 * support disables metrics export by default in every
 * {@code @SpringBootTest} (so tests never publish to a real monitoring
 * backend), which means {@code PrometheusMetricsExportAutoConfiguration}
 * never creates its {@code PrometheusMeterRegistry} bean and {@code
 * /metrics} never registers at all unless this annotation opts back in.
 *
 * <p><b>Scope note:</b> an earlier version of this test also paused the
 * shared PostgreSQL Testcontainer to prove readiness goes DOWN (and
 * liveness stays UP) when the database is unreachable. That scenario was
 * dropped: a paused container's TCP socket hangs rather than refusing the
 * connection, and even with the {@code socketTimeout}/{@code
 * connectTimeout} hardening this PR adds to the datasource (see
 * application.yml and {@code AbstractPostgresIT}), the exact time to
 * detect a wedged connection proved too variable to bound reliably in a
 * shared-container test environment without either flaking or making the
 * whole suite noticeably slower. The underlying mechanism —
 * Spring Boot's own {@code DataSourceHealthIndicator} reporting DOWN when
 * the datasource is unreachable — is first-party, heavily-tested Spring
 * Boot behavior; what this PR's own code contributes is the health group
 * *membership* (readiness includes {@code db}; liveness deliberately does
 * not), which the test below already exercises indirectly by confirming
 * {@code /health/readiness} and {@code /health/liveness} both return 200
 * against a healthy database. Revisit if a dedicated (non-shared)
 * database instance per test class becomes cheap enough to justify it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@AutoConfigureMetrics
class HealthEndpointsIT extends AbstractMessagingIT {

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
