package com.estoma.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.event.AccountActivatedPayload;
import com.estoma.authentication.contracts.event.AccountCreatedPayload;
import com.estoma.authentication.contracts.event.AccountDeactivatedPayload;
import com.estoma.communications.contracts.consumed.AccountProjectionEventPayload;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuthenticationAccountEventsContractTest {

  private final ObjectMapper mapper = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Test
  void accountCreatedIsReadableByCommunicationsAndCarriesAuthorizationPrerequisites() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    var owner = new AccountCreatedPayload(accountId, personId, LoginType.MATRICULA, "INACTIVE", Instant.now());

    String json = mapper.writeValueAsString(owner);
    AccountProjectionEventPayload communications = mapper.readValue(json, AccountProjectionEventPayload.class);
    JsonNode authorizationInput = mapper.readTree(json);

    assertThat(communications.accountId()).isEqualTo(accountId);
    assertThat(communications.personId()).isEqualTo(personId);
    assertThat(communications.status()).isEqualTo("INACTIVE");
    assertThat(authorizationInput.path("accountId").asString()).isEqualTo(accountId.toString());
    assertThat(authorizationInput.path("personId").asString()).isEqualTo(personId.toString());
    assertThat(authorizationInput.path("loginType").asString()).isEqualTo("MATRICULA");
    assertThat(authorizationInput.path("status").asString()).isEqualTo("INACTIVE");
    assertThat(authorizationInput.has("sourceVersion")).isFalse();
  }

  @Test
  void accountActivatedIsReadableWithoutConsumerInventedVersionFields() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    var owner = new AccountActivatedPayload(
        accountId, personId, "INACTIVE", "ACTIVE", "CREDENTIAL_CONFIGURED", Instant.now());

    String json = mapper.writeValueAsString(owner);
    AccountProjectionEventPayload communications = mapper.readValue(json, AccountProjectionEventPayload.class);
    JsonNode authorizationInput = mapper.readTree(json);

    assertThat(communications.accountId()).isEqualTo(accountId);
    assertThat(communications.personId()).isEqualTo(personId);
    assertThat(communications.status()).isEqualTo("ACTIVE");
    assertThat(authorizationInput.path("previousStatus").asString()).isEqualTo("INACTIVE");
    assertThat(authorizationInput.path("status").asString()).isEqualTo("ACTIVE");
    assertThat(authorizationInput.has("sourceVersion")).isFalse();
  }

  @Test
  void accountDeactivatedIsReadableByCommunicationsAndAuthorization() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    var owner = new AccountDeactivatedPayload(
        accountId, personId, "ACTIVE", "INACTIVE", "SECURITY", null, Instant.now());

    String json = mapper.writeValueAsString(owner);
    AccountProjectionEventPayload communications = mapper.readValue(json, AccountProjectionEventPayload.class);
    JsonNode authorizationInput = mapper.readTree(json);

    assertThat(communications.accountId()).isEqualTo(accountId);
    assertThat(communications.personId()).isEqualTo(personId);
    assertThat(communications.status()).isEqualTo("INACTIVE");
    assertThat(authorizationInput.path("reasonCode").asString()).isEqualTo("SECURITY");
  }
}
