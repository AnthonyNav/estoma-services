package com.estoma.users.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;

/**
 * With {@code management.endpoints.web.base-path: /}, the exposure
 * allow-list (docs/system-contract.md §11) is the only thing standing
 * between the network and a heap dump — this is load-bearing, not a
 * nice-to-have.
 *
 * <p>These paths never even reach Actuator's own "is this endpoint
 * exposed" check: {@code HttpSecurityConfig}'s {@code anyRequest().
 * denyAll()} intercepts first, so the response is Spring Security's 403,
 * not Actuator's 404 — an attacker can't even tell the difference between
 * "not exposed" and "doesn't exist," which is the stronger of the two
 * guarantees.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class EndpointExposureIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void envIsNotExposed() {
    assertDenied("/env");
  }

  @Test
  void beansIsNotExposed() {
    assertDenied("/beans");
  }

  @Test
  void heapdumpIsNotExposed() {
    assertDenied("/heapdump");
  }

  @Test
  void configpropsIsNotExposed() {
    assertDenied("/configprops");
  }

  @Test
  void loggersIsNotExposed() {
    assertDenied("/loggers");
  }

  @Test
  void threaddumpIsNotExposed() {
    assertDenied("/threaddump");
  }

  private void assertDenied(String path) {
    assertThat(restTemplate.getForEntity(path, String.class).getStatusCode().value()).isEqualTo(403);
  }
}
