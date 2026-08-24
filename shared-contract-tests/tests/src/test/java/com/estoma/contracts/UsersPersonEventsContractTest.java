package com.estoma.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.consumed.PersonStatusEventPayload;
import com.estoma.communications.contracts.consumed.PersonProjectionEventPayload;
import com.estoma.users.contract.event.PersonActivatedPayload;
import com.estoma.users.contract.event.PersonCreatedPayload;
import com.estoma.users.contract.event.PersonDeactivatedPayload;
import com.estoma.users.contract.event.PersonUpdatedPayload;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

class UsersPersonEventsContractTest {

  private final ObjectMapper mapper = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Test
  void personCreatedIsReadableByAuthenticationAndCommunications() {
    UUID personId = UUID.randomUUID();
    var owner = new PersonCreatedPayload(
        personId, "Ana", "López", "García", "ana@estoma.edu", "5550000000", "ACTIVE", 7, Instant.now());

    String json = mapper.writeValueAsString(owner);
    PersonProjectionEventPayload communications = mapper.readValue(json, PersonProjectionEventPayload.class);
    PersonStatusEventPayload authentication = mapper.readValue(json, PersonStatusEventPayload.class);

    assertThat(communications.personId()).isEqualTo(personId);
    assertThat(communications.displayName()).isEqualTo("Ana López García");
    assertThat(communications.institutionalEmail()).isEqualTo("ana@estoma.edu");
    assertThat(communications.status()).isEqualTo("ACTIVE");
    assertThat(authentication.personId()).isEqualTo(personId);
    assertThat(authentication.status()).isEqualTo("ACTIVE");
  }

  @Test
  void personUpdatedCarriesTheIdentityFieldsCommunicationsNeeds() {
    UUID personId = UUID.randomUUID();
    var owner = new PersonUpdatedPayload(
        personId, "Ana María", "López", "García", "ana@estoma.edu", null, 8, Instant.now());

    PersonProjectionEventPayload communications = mapper.readValue(
        mapper.writeValueAsString(owner), PersonProjectionEventPayload.class);

    assertThat(communications.personId()).isEqualTo(personId);
    assertThat(communications.displayName()).isEqualTo("Ana María López García");
    assertThat(communications.institutionalEmail()).isEqualTo("ana@estoma.edu");
  }

  @Test
  void statusOnlyPersonEventsRemainReadableWithoutErasingIdentityAssumptions() {
    UUID personId = UUID.randomUUID();
    var activated = new PersonActivatedPayload(personId, "ACTIVE", 9, Instant.now());
    var deactivated = new PersonDeactivatedPayload(personId, "INACTIVE", 10, Instant.now(), "ADMINISTRATIVE", null);

    PersonProjectionEventPayload activeProjection = mapper.readValue(
        mapper.writeValueAsString(activated), PersonProjectionEventPayload.class);
    PersonProjectionEventPayload inactiveProjection = mapper.readValue(
        mapper.writeValueAsString(deactivated), PersonProjectionEventPayload.class);
    PersonStatusEventPayload authentication = mapper.readValue(
        mapper.writeValueAsString(deactivated), PersonStatusEventPayload.class);

    assertThat(activeProjection.personId()).isEqualTo(personId);
    assertThat(activeProjection.status()).isEqualTo("ACTIVE");
    assertThat(activeProjection.institutionalEmail()).isNull();
    assertThat(inactiveProjection.status()).isEqualTo("INACTIVE");
    assertThat(authentication.status()).isEqualTo("INACTIVE");
  }
}
