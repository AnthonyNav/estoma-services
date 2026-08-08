package com.estoma.communications.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Verifies {@code HttpSecurityConfig}: everything except the operational GET
 * endpoints is denied, no session is ever created, and the two mandatory
 * security headers are present (system_contract.md "Operational HTTP
 * Interface").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HttpSecurityIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void postToAnAllowedPathIsStillDenied() {
    ResponseEntity<String> response = restTemplate.postForEntity("/health", null, String.class);

    assertThat(response.getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  void anUndeclaredPathIsDenied() {
    ResponseEntity<String> response = restTemplate.getForEntity("/communications", String.class);

    assertThat(response.getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  void noSessionCookieIsEverSet() {
    ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

    assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
  }

  @Test
  void securityHeadersArePresentOnAnAllowedResponse() {
    ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

    assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
  }
}
