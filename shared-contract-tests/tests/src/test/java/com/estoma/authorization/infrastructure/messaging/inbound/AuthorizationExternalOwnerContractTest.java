package com.estoma.authorization.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.application.projection.AcademicEligibilityProjectionEvent;
import com.estoma.authorization.application.projection.AccountProjectionEvent;
import com.estoma.authorization.application.projection.PersonProjectionEvent;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.event.AccountActivatedPayload;
import com.estoma.authentication.contracts.event.AccountCreatedPayload;
import com.estoma.authentication.contracts.event.AccountDeactivatedPayload;
import com.estoma.users.contract.event.PersonCreatedPayload;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuthorizationExternalOwnerContractTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final ExternalEventMessageMapper mapping =
      new ExternalEventMessageMapper(new EnvelopeJsonCodec(mapper));

  @Test
  void realUsersPersonCreatedMapsIntoAuthorizationProjection() {
    UUID personId = UUID.randomUUID();
    int version = 7;
    var owner = new PersonCreatedPayload(
        personId, "Ana", "López", null, "ana@estoma.edu", null, "ACTIVE", version, Instant.now());
    MessageEnvelope envelope = event(
        "PersonCreated", "users", personId, version, mapper.valueToTree(owner));

    var projected = mapping.map(envelope);

    assertThat(projected).isInstanceOf(PersonProjectionEvent.class);
    PersonProjectionEvent person = (PersonProjectionEvent) projected;
    assertThat(person.personId()).isEqualTo(personId);
    assertThat(person.metadata().aggregateVersion()).isEqualTo(version);
  }

  @Test
  void realAuthenticationAccountLifecycleMapsIntoAuthorizationProjection() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    var created = new AccountCreatedPayload(accountId, personId, LoginType.MATRICULA, "INACTIVE", Instant.now());
    var activated = new AccountActivatedPayload(accountId, personId, "INACTIVE", "ACTIVE", "CREDENTIAL_CONFIGURED", Instant.now());
    var deactivated = new AccountDeactivatedPayload(accountId, personId, "ACTIVE", "INACTIVE", "SECURITY", null, Instant.now());

    AccountProjectionEvent createdProjection = (AccountProjectionEvent) mapping.map(
        event("AccountCreated", "authentication", accountId, 1, mapper.valueToTree(created)));
    AccountProjectionEvent activeProjection = (AccountProjectionEvent) mapping.map(
        event("AccountActivated", "authentication", accountId, 2, mapper.valueToTree(activated)));
    AccountProjectionEvent inactiveProjection = (AccountProjectionEvent) mapping.map(
        event("AccountDeactivated", "authentication", accountId, 3, mapper.valueToTree(deactivated)));

    assertThat(createdProjection.accountId()).isEqualTo(accountId);
    assertThat(createdProjection.personId()).isEqualTo(personId);
    assertThat(createdProjection.status().name()).isEqualTo("INACTIVE");
    assertThat(activeProjection.status().name()).isEqualTo("ACTIVE");
    assertThat(inactiveProjection.status().name()).isEqualTo("INACTIVE");
  }

  @Test
  void academicEligibilityShapeMapsWithEligibilityVersionAsAggregateVersion() {
    UUID eligibilityId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    int eligibilityVersion = 4;
    var payload = mapper.createObjectNode()
        .put("eligibilityId", eligibilityId.toString())
        .put("studentAcademicProfileId", profileId.toString())
        .put("accountId", accountId.toString())
        .put("systemCode", "PRACTICAS_PROFESIONALES")
        .put("eligible", true)
        .put("policyVersion", 1)
        .put("eligibilityVersion", eligibilityVersion)
        .put("academicProfileVersion", 9)
        .put("evaluatedAt", Instant.now().toString());

    AcademicEligibilityProjectionEvent projected = (AcademicEligibilityProjectionEvent) mapping.map(
        event("StudentAcademicEligibilityChanged", "academic", eligibilityId, eligibilityVersion, payload));

    assertThat(projected.eligibilityId()).isEqualTo(eligibilityId);
    assertThat(projected.studentAcademicProfileId()).isEqualTo(profileId);
    assertThat(projected.accountId()).isEqualTo(accountId);
    assertThat(projected.eligibilityVersion()).isEqualTo(eligibilityVersion);
    assertThat(projected.eligible()).isTrue();
  }

  private static MessageEnvelope event(
      String type, String producer, UUID aggregateId, int aggregateVersion, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.EVENT, producer, Instant.now(), UUID.randomUUID(), null,
        null, null, aggregateId, aggregateVersion, new WireActor("SYSTEM", null, producer), payload);
  }
}
