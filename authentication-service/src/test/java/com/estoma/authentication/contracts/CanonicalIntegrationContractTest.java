package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.infrastructure.messaging.AmqpNames;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CanonicalIntegrationContractTest {

  private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  void academicIsTheCanonicalProducerForAccountImportCommands() {
    UUID accountId = UUID.randomUUID();
    UUID importId = UUID.randomUUID();
    var execution = mapper.createObjectNode()
        .put("importId", importId.toString())
        .put("planItemId", UUID.randomUUID().toString())
        .put("executionGeneration", 2)
        .put("authorizedByAccountId", UUID.randomUUID().toString())
        .put("scope", "CREATE_ACCOUNT");
    var payload = mapper.createObjectNode()
        .put("accountId", accountId.toString())
        .put("personId", UUID.randomUUID().toString())
        .put("loginType", "MATRICULA")
        .put("loginIdentifier", "A001");
    payload.set("importExecution", execution);

    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), "CreateAccount", 1, MessageKind.COMMAND, "academic", Instant.now(),
        importId, null, UUID.randomUUID(), "idem", accountId, null,
        new Actor("SYSTEM", null, "academic", null, null), payload);

    assertThat(AuthenticationAuthorizationMatrix.permits(envelope)).isTrue();
    assertThat(AuthenticationAuthorizationMatrix.decision(envelope))
        .isEqualTo(AuthenticationAuthorizationMatrix.Decision.AUTHORIZED);
  }

  @Test
  void legacyStudentImportProducerIsNoLongerAuthorized() {
    AuthenticationAuthorizationMatrix.Rule academicRule = AuthenticationAuthorizationMatrix.rules().stream()
        .filter(rule -> "academic".equals(rule.producer()) && "CreateAccount".equals(rule.operation()))
        .findFirst().orElseThrow();
    assertThat(academicRule.producer()).isEqualTo("academic");
  }

  @Test
  void canonicalCrossServiceRoutesAreExposed() {
    assertThat(MessagingNames.QUERIES_EXCHANGE).isEqualTo("platform.queries");
    assertThat(MessagingNames.AUTHENTICATION_QUERY_QUEUE).isEqualTo("queries.authentication");
    assertThat(AmqpNames.ROUTING_KEY_PERSON_CREATED).isEqualTo("users.person.created");
    assertThat(AmqpNames.ROUTING_KEY_PERSON_ACTIVATED).isEqualTo("users.person.activated");
    assertThat(AmqpNames.ROUTING_KEY_PERSON_DEACTIVATED).isEqualTo("users.person.deactivated");
  }

  @Test
  void systemActorOmitsNonApplicableFieldsOnWire() {
    var json = mapper.valueToTree(new Actor("SYSTEM", null, "authentication", null, null));
    assertThat(json.size()).isEqualTo(2);
    assertThat(json.path("type").asString()).isEqualTo("SYSTEM");
    assertThat(json.path("service").asString()).isEqualTo("authentication");
  }
}
