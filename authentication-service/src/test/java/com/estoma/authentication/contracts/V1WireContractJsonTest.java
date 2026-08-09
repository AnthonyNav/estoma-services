package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.result.ResultStatus;
import com.estoma.authentication.contracts.event.AccountDeactivatedPayload;
import com.estoma.authentication.contracts.event.AllAccountSessionsRevokedPayload;
import com.estoma.authentication.contracts.event.LoginIdentifierChangedPayload;
import com.estoma.authentication.contracts.event.PasswordChangedPayload;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class V1WireContractJsonTest {

  private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  void securityRpcRequestUsesTheCommonEnvelopeAndCarriesActorIdentity() {
    UUID accountId = UUID.randomUUID();
    MessageEnvelope request = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.CHANGE_PASSWORD.wireName(), 1, MessageKind.RPC_REQUEST, "bff",
        Instant.parse("2026-08-08T00:00:00Z"), UUID.randomUUID(), null, null, null, accountId, null,
        new Actor("ACCOUNT", accountId, null, "SELF", null), mapper.createObjectNode());

    var json = mapper.readTree(mapper.writeValueAsBytes(request));

    assertThat(json.get("messageKind").asString()).isEqualTo("RPC_REQUEST");
    assertThat(json.get("actor").get("accountId").asString()).isEqualTo(accountId.toString());
    assertThat(json.has("targetService")).isFalse();
    assertThat(json.has("targetQueue")).isFalse();
    assertThat(json.has("destination")).isFalse();
    assertThat(json.has("replyService")).isFalse();
  }

  @Test
  void commonResultUsesOnlyStatusDataAndError() {
    var json = mapper.readTree(mapper.writeValueAsBytes(ResultPayload.rejected("ACTOR_NOT_AUTHORIZED")));

    assertThat(ResultStatus.values()).containsExactly(ResultStatus.SUCCEEDED, ResultStatus.REJECTED, ResultStatus.FAILED, ResultStatus.EXPIRED);
    assertThat(json.size()).isEqualTo(3);
    assertThat(json.has("data")).isTrue();
    assertThat(json.has("error")).isTrue();
    assertThat(json.get("status").asString()).isEqualTo("REJECTED");
    assertThat(json.get("error").get("code").asString()).isEqualTo("ACTOR_NOT_AUTHORIZED");
  }

  @Test
  void envelopeDoesNotDeclareRoutingOrReplyMetadata() {
    var fields = Arrays.stream(MessageEnvelope.class.getRecordComponents()).map(RecordComponent::getName).toList();

    assertThat(fields).doesNotContain("targetService", "targetQueue", "destination", "replyService");
  }

  @Test
  void eventPayloadsUseV1SourceAndReasonCodeFieldNames() {
    UUID accountId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-08T00:00:00Z");

    var password = mapper.readTree(mapper.writeValueAsBytes(new PasswordChangedPayload(accountId, "password_reset", now, true)));
    var login = mapper.readTree(mapper.writeValueAsBytes(new LoginIdentifierChangedPayload(accountId, LoginType.MATRICULA, "command", 3, now)));
    var deactivation = mapper.readTree(mapper.writeValueAsBytes(new AccountDeactivatedPayload(accountId, UUID.randomUUID(), "INACTIVE", 3, "ADMINISTRATIVE", now)));
    var sessions = mapper.readTree(mapper.writeValueAsBytes(new AllAccountSessionsRevokedPayload(accountId, "person_deactivated", now)));

    assertThat(password.get("changeSource").asString()).isEqualTo("password_reset");
    assertThat(login.get("changeSource").asString()).isEqualTo("command");
    assertThat(deactivation.get("reasonCode").asString()).isEqualTo("ADMINISTRATIVE");
    assertThat(sessions.get("reasonCode").asString()).isEqualTo("person_deactivated");
    assertThat(sessions.has("reason")).isFalse();
  }
}
