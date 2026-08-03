package com.estoma.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.testsupport.AbstractPostgresIT;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Interim replacement for the pre-existing {@code UsersApplicationTests}
 * smoke test: adding a mandatory {@code spring.datasource}/{@code
 * spring.flyway} configuration means every {@code @SpringBootTest} now
 * needs a real database to boot the context, so this moved from a
 * container-free unit test to an {@code AbstractPostgresIT}-backed one.
 *
 * <p>This class — and {@link SystemController} itself — are replaced by
 * {@code HealthEndpointsIT} / {@code EndpointExposureIT} / {@code
 * HttpSecurityIT} once the documented Actuator health paths are wired up
 * (docs/system-contract.md §11); see the commit that deletes {@link
 * SystemController}.
 */
class SystemControllerIT extends AbstractPostgresIT {

  private final SystemController controller = new SystemController();

  @Test
  void contextLoads() {}

  @Test
  void healthReturnsOk() {
    assertThat(controller.health())
        .containsAllEntriesOf(Map.of("status", "ok", "service", "users-service"));
  }

  @Test
  void versionReturnsServiceVersion() {
    assertThat(controller.version())
        .containsAllEntriesOf(Map.of("service", "users-service", "version", "0.1.0"));
  }
}
