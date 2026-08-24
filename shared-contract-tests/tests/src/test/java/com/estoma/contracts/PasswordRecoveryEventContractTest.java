package com.estoma.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.event.PasswordRecoveryRequestedPayload;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PasswordRecoveryEventContractTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void authenticationRecoveryFactRoundTripsIntoCommunicationsWithoutASecret() {
    UUID recoveryId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    Instant requestedAt = Instant.now();
    Instant expiresAt = requestedAt.plusSeconds(600);
    var owner = new PasswordRecoveryRequestedPayload(recoveryId, accountId, personId, expiresAt, requestedAt);

    String json = mapper.writeValueAsString(owner);
    var consumer = mapper.readValue(
        json, com.estoma.communications.contracts.consumed.PasswordRecoveryRequestedPayload.class);
    var tree = mapper.readTree(json);

    assertThat(consumer.recoveryId()).isEqualTo(recoveryId);
    assertThat(consumer.accountId()).isEqualTo(accountId);
    assertThat(consumer.personId()).isEqualTo(personId);
    assertThat(consumer.expiresAt()).isEqualTo(expiresAt);
    assertThat(consumer.requestedAt()).isEqualTo(requestedAt);

    assertThat(tree.has("code")).isFalse();
    assertThat(tree.has("token")).isFalse();
    assertThat(tree.has("secret")).isFalse();
    assertThat(json.toLowerCase()).doesNotContain("password");
  }
}
