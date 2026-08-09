package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.result.ResultStatus;
import com.estoma.authentication.contracts.command.ActivateAccountCommand;
import com.estoma.authentication.contracts.command.ChangeLoginIdentifierCommand;
import com.estoma.authentication.contracts.command.CreateAccountCommand;
import com.estoma.authentication.contracts.command.DeactivateAccountCommand;
import com.estoma.authentication.contracts.command.RequestPasswordRecoveryCommand;
import com.estoma.authentication.contracts.command.RevokeAllAccountSessionsCommand;
import com.estoma.authentication.contracts.event.AccountActivatedPayload;
import com.estoma.authentication.contracts.event.AccountCreatedPayload;
import com.estoma.authentication.contracts.event.AccountDeactivatedPayload;
import com.estoma.authentication.contracts.event.AllAccountSessionsRevokedPayload;
import com.estoma.authentication.contracts.event.LoginIdentifierChangedPayload;
import com.estoma.authentication.contracts.event.OtherAccountSessionsRevokedPayload;
import com.estoma.authentication.contracts.event.PasswordChangedPayload;
import com.estoma.authentication.contracts.event.PasswordRecoveryRequestedPayload;
import com.estoma.authentication.contracts.event.SessionRevokedPayload;
import com.estoma.authentication.contracts.event.StudentLoginIdentifierSynchronizationFailedPayload;
import com.estoma.authentication.contracts.event.TemporaryPasswordRegeneratedPayload;
import com.estoma.authentication.contracts.event.TemporaryPasswordSetPayload;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountResponse;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.ChangePasswordResponse;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryRequest;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryResponse;
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionResponse;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordResponse;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.ResetPasswordResponse;
import com.estoma.authentication.contracts.rpc.RevokeSessionRequest;
import com.estoma.authentication.contracts.rpc.RevokeSessionResponse;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordResponse;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
    assertThat(json.size()).isEqualTo(2);
    assertThat(json.has("data")).isFalse();
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

  @Test
  void everyV1CommandAndEventFixtureRoundTripsInTheCommonEnvelope() throws Exception {
    assertCommonEnvelopeFixtures(
        MessageKind.COMMAND,
        List.of(
            ActivateAccountCommand.class,
            ChangeLoginIdentifierCommand.class,
            CreateAccountCommand.class,
            DeactivateAccountCommand.class,
            RequestPasswordRecoveryCommand.class,
            RevokeAllAccountSessionsCommand.class));
    assertCommonEnvelopeFixtures(
        MessageKind.EVENT,
        List.of(
            AccountActivatedPayload.class,
            AccountCreatedPayload.class,
            AccountDeactivatedPayload.class,
            AllAccountSessionsRevokedPayload.class,
            LoginIdentifierChangedPayload.class,
            OtherAccountSessionsRevokedPayload.class,
            PasswordChangedPayload.class,
            PasswordRecoveryRequestedPayload.class,
            SessionRevokedPayload.class,
            StudentLoginIdentifierSynchronizationFailedPayload.class,
            TemporaryPasswordRegeneratedPayload.class,
            TemporaryPasswordSetPayload.class));
  }

  @Test
  void everyV1RpcFixtureRoundTripsInTheCommonEnvelope() throws Exception {
    assertCommonEnvelopeFixtures(
        MessageKind.RPC_REQUEST,
        List.of(
            AuthenticateAccountRequest.class,
            ChangePasswordRequest.class,
            GetRecoveryCodeForDeliveryRequest.class,
            RefreshSessionRequest.class,
            RegenerateTemporaryPasswordRequest.class,
            ResetPasswordRequest.class,
            RevokeSessionRequest.class,
            SetTemporaryPasswordRequest.class,
            ValidateRecoveryCodeRequest.class));
    assertCommonEnvelopeFixtures(
        MessageKind.RPC_RESPONSE,
        List.of(
            AuthenticateAccountResponse.class,
            ChangePasswordResponse.class,
            GetRecoveryCodeForDeliveryResponse.class,
            RefreshSessionResponse.class,
            RegenerateTemporaryPasswordResponse.class,
            ResetPasswordResponse.class,
            RevokeSessionResponse.class,
            SetTemporaryPasswordResponse.class,
            ValidateRecoveryCodeResponse.class));
  }

  @Test
  void everyResultStatusUsesTheCommonResultEnvelopePayload() throws Exception {
    List<ResultPayload> fixtures = List.of(
        ResultPayload.succeeded(new com.estoma.authentication.contracts.result.SucceededPayload(UUID.randomUUID(), 7)),
        ResultPayload.rejected("FIXTURE_REJECTED"),
        ResultPayload.failed("FIXTURE_FAILED"),
        new ResultPayload(ResultStatus.EXPIRED, null, null));

    for (ResultPayload fixture : fixtures) {
      MessageEnvelope envelope = envelope(MessageKind.RESULT, fixture);
      var json = mapper.readTree(mapper.writeValueAsBytes(envelope));
      assertThat(json.get("payload").get("status").asString()).isEqualTo(fixture.status().name());
      assertThat(mapper.readValue(mapper.writeValueAsBytes(envelope), MessageEnvelope.class)).isEqualTo(envelope);
    }
  }

  private void assertCommonEnvelopeFixtures(MessageKind kind, List<Class<?>> payloadTypes) throws Exception {
    for (Class<?> payloadType : payloadTypes) {
      Object payload = fixture(payloadType);
      MessageEnvelope envelope = envelope(kind, payload);
      byte[] bytes = mapper.writeValueAsBytes(envelope);
      var json = mapper.readTree(bytes);

      assertThat(json.get("messageKind").asString()).isEqualTo(kind.name());
      assertThat(json.get("messageType").asString()).isEqualTo(payloadType.getSimpleName());
      assertThat(json.has("targetService")).isFalse();
      assertThat(json.has("targetQueue")).isFalse();
      assertThat(json.has("destination")).isFalse();
      assertThat(json.has("replyService")).isFalse();
      assertThat(mapper.readValue(bytes, MessageEnvelope.class)).isEqualTo(envelope);
      assertThat(mapper.treeToValue(json.get("payload"), payloadType)).isEqualTo(payload);
    }
  }

  private MessageEnvelope envelope(MessageKind kind, Object payload) {
    UUID accountId = UUID.randomUUID();
    return new MessageEnvelope(
        UUID.randomUUID(), payload.getClass().getSimpleName(), 1, kind, "fixture", Instant.parse("2026-08-08T00:00:00Z"),
        UUID.randomUUID(), UUID.randomUUID(), kind == MessageKind.RPC_REQUEST || kind == MessageKind.RPC_RESPONSE ? null : UUID.randomUUID(),
        kind == MessageKind.COMMAND ? "fixture-idempotency-key" : null, accountId, 7,
        new Actor("ACCOUNT", accountId, null, "SELF", null), mapper.valueToTree(payload));
  }

  private static Object fixture(Class<?> type) throws Exception {
    RecordComponent[] components = type.getRecordComponents();
    Object[] values = new Object[components.length];
    Class<?>[] parameterTypes = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      parameterTypes[i] = components[i].getType();
      values[i] = fixtureValue(parameterTypes[i]);
    }
    return type.getDeclaredConstructor(parameterTypes).newInstance(values);
  }

  private static Object fixtureValue(Class<?> type) {
    if (type == UUID.class) return UUID.fromString("11111111-1111-1111-1111-111111111111");
    if (type == Instant.class) return Instant.parse("2026-08-08T00:00:00Z");
    if (type == String.class) return "fixture-value";
    if (type == int.class || type == Integer.class) return 7;
    if (type == boolean.class || type == Boolean.class) return true;
    if (type.isEnum()) return type.getEnumConstants()[0];
    throw new IllegalArgumentException("No JSON fixture value for " + type.getName());
  }
}
