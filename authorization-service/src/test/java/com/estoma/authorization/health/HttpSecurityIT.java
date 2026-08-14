package com.estoma.authorization.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HttpSecurityIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void operationalHealthIsPublicButBusinessRoutesAreDenied() {
    assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode().value())
        .isEqualTo(200);
    assertThat(restTemplate.getForEntity("/roles", String.class).getStatusCode().value())
        .isEqualTo(403);
    assertThat(restTemplate.getForEntity("/does-not-exist", String.class).getStatusCode().value())
        .isEqualTo(403);
    assertThat(restTemplate.exchange("/actuator/health", HttpMethod.POST, null, String.class)
            .getStatusCode()
            .value())
        .isEqualTo(403);
  }

  @Test
  void responsesAreStatelessAndHardened() {
    ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

    assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void nonOperationalActuatorEndpointsAreDenied() {
    assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode().value())
        .isEqualTo(403);
    assertThat(restTemplate.getForEntity("/actuator/beans", String.class).getStatusCode().value())
        .isEqualTo(403);
    assertThat(restTemplate.getForEntity("/actuator/heapdump", String.class).getStatusCode().value())
        .isEqualTo(403);
  }
}
