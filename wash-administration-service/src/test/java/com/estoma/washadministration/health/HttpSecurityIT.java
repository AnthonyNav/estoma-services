package com.estoma.washadministration.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washadministration.testsupport.AbstractPostgresIT;
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
  void businessAndUnknownRoutesAreDenied() {
    assertThat(restTemplate.getForEntity("/calendars", String.class).getStatusCode().value()).isEqualTo(403);
    assertThat(restTemplate.getForEntity("/does-not-exist", String.class).getStatusCode().value()).isEqualTo(403);
    assertThat(restTemplate.postForEntity("/health", null, String.class).getStatusCode().value()).isEqualTo(403);
  }

  @Test
  void nonOperationalActuatorEndpointsAreDenied() {
    assertThat(restTemplate.getForEntity("/env", String.class).getStatusCode().value()).isEqualTo(403);
    assertThat(restTemplate.getForEntity("/beans", String.class).getStatusCode().value()).isEqualTo(403);
    assertThat(restTemplate.getForEntity("/heapdump", String.class).getStatusCode().value()).isEqualTo(403);
  }

  @Test
  void allowedOperationalRoutesAreGetOnlyAndStateless() {
    ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(restTemplate.exchange("/health", HttpMethod.POST, null, String.class).getStatusCode().value())
        .isEqualTo(403);
  }
}
