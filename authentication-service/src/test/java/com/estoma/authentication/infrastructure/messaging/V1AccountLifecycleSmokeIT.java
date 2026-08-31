package com.estoma.authentication.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.result.ResultStatus;
import com.estoma.authentication.contracts.command.ActivateAccountCommand;
import com.estoma.authentication.contracts.command.CreateAccountCommand;
import com.estoma.authentication.contracts.command.RequestPasswordRecoveryCommand;
import com.estoma.authentication.contracts.consumed.ConsumedEventTypes;
import com.estoma.authentication.contracts.consumed.PersonStatusEventPayload;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountResponse;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.ChangePasswordResponse;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryRequest;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryResponse;
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionResponse;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.ResetPasswordResponse;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordResponse;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.session.Session;
import com.estoma.authentication.testsupport.AbstractRabbitMqIT;
import com.estoma.authentication.testsupport.Envelopes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V1 account lifecycle smoke test. Every durable and ephemeral integration
 * edge crosses a real RabbitMQ broker.
 */
class V1AccountLifecycleSmokeIT extends AbstractRabbitMqIT {

  private static final String NORMAL_PASSWORD = "a-normal-password-123";
  private static final String RESET_PASSWORD = "a-reset-password-456";

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private AccountRepository accountRepository;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcClient jdbc;

  @Test
  void completesTheV1AccountAndRecoveryLifecycleWithoutDurablyPublishingSecrets() throws Exception {
    UUID personId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String login = "v1-smoke-" + accountId;

    publishPersonCreated(personId);
    await(() -> personStatusProjectionRepository.findByPersonId(personId).isPresent(), "person creation projection");
    publishCommand(new CreateAccountCommand(accountId, personId, LoginType.USERNAME, login));
    await(() -> accountRepository.findById(accountId).isPresent(), "account creation");

    SetTemporaryPasswordResponse temporaryPassword = rpcSuccess(
        AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD,
        new SetTemporaryPasswordRequest(accountId, accountId, "v1-smoke-" + UUID.randomUUID()),
        accountId,
        new Actor("ACCOUNT", accountId, null, "GLOBAL", null),
        SetTemporaryPasswordResponse.class);

    // Accounts are intentionally inactive after CreateAccount; activation is the required command once a credential exists.
    publishCommand(new ActivateAccountCommand(accountId, 1));
    await(() -> accountRepository.findById(accountId).orElseThrow().isActive(), "account activation");

    AuthenticateAccountResponse restrictedSession = rpcSuccess(
        AuthenticationRpcOperation.AUTHENTICATE_ACCOUNT,
        new AuthenticateAccountRequest(LoginType.USERNAME, login, temporaryPassword.temporaryPassword(), null, null, null),
        null,
        anonymousActor(),
        AuthenticateAccountResponse.class);
    assertThat(restrictedSession.authState()).isEqualTo(com.estoma.authentication.contracts.rpc.AuthState.PASSWORD_CHANGE_REQUIRED);
    assertThat(restrictedSession.refreshToken()).isNull();
    assertThat(sessionRepository.findById(restrictedSession.sessionId()).orElseThrow().authState())
        .isEqualTo(Session.AuthState.PASSWORD_CHANGE_REQUIRED);

    ResultPayload restrictedRefresh = rpcResult(
        AuthenticationRpcOperation.REFRESH_SESSION,
        new RefreshSessionRequest(restrictedSession.sessionId(), "not-issued"),
        null,
        anonymousActor());
    assertThat(restrictedRefresh.status()).isEqualTo(ResultStatus.REJECTED);
    assertThat(restrictedRefresh.error().code()).isEqualTo(RejectionCode.INVALID_REFRESH_TOKEN.name());

    ChangePasswordResponse passwordChanged = rpcSuccess(
        AuthenticationRpcOperation.CHANGE_PASSWORD,
         new ChangePasswordRequest(restrictedSession.sessionId(), null, NORMAL_PASSWORD),
        accountId,
        accountActor(accountId),
        ChangePasswordResponse.class);
    assertThat(sessionRepository.findById(restrictedSession.sessionId()).orElseThrow().authState())
        .isEqualTo(Session.AuthState.NORMAL);
    assertThat(passwordChanged.refreshToken()).isNotBlank();
    assertThat(passwordChanged.sessionId()).isEqualTo(restrictedSession.sessionId());
    assertThat(passwordChanged.authState()).isEqualTo(com.estoma.authentication.contracts.rpc.AuthState.NORMAL);

    RefreshSessionResponse refreshed = rpcSuccess(
        AuthenticationRpcOperation.REFRESH_SESSION,
        new RefreshSessionRequest(restrictedSession.sessionId(), passwordChanged.refreshToken()),
        null,
        anonymousActor(),
        RefreshSessionResponse.class);
    assertThat(refreshed.refreshToken()).isNotBlank();

    publishCommand(new RequestPasswordRecoveryCommand(LoginType.USERNAME, login));
    UUID recoveryId = awaitRecoveryId(accountId);
    String recoveryCode = requestRecoveryCodeAsCommunicationsSystem(recoveryId);

    ValidateRecoveryCodeResponse validation = rpcSuccess(
        AuthenticationRpcOperation.VALIDATE_RECOVERY_CODE,
        new ValidateRecoveryCodeRequest(LoginType.USERNAME, login, recoveryCode),
        null,
        anonymousActor(),
        ValidateRecoveryCodeResponse.class);
    assertThat(validation.result()).isEqualTo("VALID");

    ResetPasswordResponse reset = rpcSuccess(
        AuthenticationRpcOperation.RESET_PASSWORD,
        new ResetPasswordRequest(LoginType.USERNAME, login, recoveryCode, RESET_PASSWORD),
        null,
        anonymousActor(),
        ResetPasswordResponse.class);
    assertThat(reset.reset()).isTrue();
    assertThat(sessionRepository.findById(restrictedSession.sessionId()).orElseThrow().isValid(java.time.Instant.now())).isFalse();
    assertNoDurablePayloadContains(temporaryPassword.temporaryPassword(), NORMAL_PASSWORD, RESET_PASSWORD, recoveryCode, restrictedSession.accessToken(), refreshed.refreshToken());
  }

  private void publishPersonCreated(UUID personId) {
    MessageEnvelope event = Envelopes.event(
        ConsumedEventTypes.PERSON_CREATED, new PersonStatusEventPayload(personId, "ACTIVE", 1), personId, 1);
    rabbitTemplate.send(MessagingNames.EVENTS_EXCHANGE, AmqpNames.ROUTING_KEY_PERSON_CREATED, new Message(codec.encode(event)));
  }

  private void publishCommand(Object payload) {
    String type = switch (payload) {
      case CreateAccountCommand ignored -> AuthenticationCommand.CREATE_ACCOUNT.wireName();
      case ActivateAccountCommand ignored -> AuthenticationCommand.ACTIVATE_ACCOUNT.wireName();
      case RequestPasswordRecoveryCommand ignored -> AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName();
      default -> throw new IllegalArgumentException("unsupported smoke command " + payload.getClass());
    };
    rabbitTemplate.send(MessagingNames.COMMANDS_EXCHANGE, AmqpNames.PRODUCER, new Message(codec.encode(Envelopes.command(type, payload))));
  }

  private String requestRecoveryCodeAsCommunicationsSystem(UUID recoveryId) {
    GetRecoveryCodeForDeliveryResponse response = rpcSuccess(
        AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY,
        new GetRecoveryCodeForDeliveryRequest(recoveryId),
        null,
        new Actor("SYSTEM", null, "communications", "GLOBAL", null),
        GetRecoveryCodeForDeliveryResponse.class);
    return response.code();
  }

  private <T> T rpcSuccess(
      AuthenticationRpcOperation operation, Object payload, UUID aggregateId, Actor actor, Class<T> responseType) {
    ResultPayload result = rpcResult(operation, payload, aggregateId, actor);
     assertThat(result.status()).isEqualTo(ResultStatus.SUCCEEDED);
    return codec.convertPayload(new MessageEnvelope(
        UUID.randomUUID(), operation.wireName(), 1, MessageKind.RPC_RESPONSE, "authentication", java.time.Instant.now(),
        UUID.randomUUID(), UUID.randomUUID(), null, null, aggregateId, null, actor, codec.toJsonNode(result.data())), responseType);
  }

  private ResultPayload rpcResult(AuthenticationRpcOperation operation, Object payload, UUID aggregateId, Actor actor) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setReplyTo("amq.rabbitmq.reply-to");
    MessageEnvelope request = new MessageEnvelope(
         UUID.randomUUID(), operation.wireName(), 1, MessageKind.RPC_REQUEST,
         actor.service() == null ? "bff" : actor.service(), java.time.Instant.now(), UUID.randomUUID(), null, null,
         switch (payload) {
           case SetTemporaryPasswordRequest temporary -> temporary.idempotencyKey();
           default -> null;
         },
         aggregateId, null, actor, codec.toJsonNode(payload));
    Message reply = rabbitTemplate.sendAndReceive(
        MessagingNames.SECURITY_RPC_EXCHANGE,
        MessagingNames.AUTHENTICATION_RPC_QUEUE,
        new Message(codec.encode(request), properties));

    assertThat(reply).isNotNull();
    MessageEnvelope response = codec.decode(reply.getBody());
    assertThat(response.messageKind()).isEqualTo(MessageKind.RPC_RESPONSE);
    assertThat(response.messageType()).isEqualTo(operation.wireName());
    assertThat(response.producer()).isEqualTo("authentication");
    assertThat(response.correlationId()).isEqualTo(request.correlationId());
    assertThat(response.causationId()).isEqualTo(request.messageId());
    assertThat(response.operationId()).isNull();
    assertThat(response.idempotencyKey()).isNull();
    assertThat(response.aggregateId()).isEqualTo(aggregateId);
    assertThat(response.actor()).isEqualTo(actor);
    ResultPayload result = codec.convertPayload(response, ResultPayload.class);
    return result;
  }

  private static Actor anonymousActor() {
    return new Actor("ANONYMOUS", null, null, "PUBLIC", null);
  }

  private static Actor accountActor(UUID accountId) {
    return new Actor("ACCOUNT", accountId, null, "SELF", null);
  }

  private UUID awaitRecoveryId(UUID accountId) throws InterruptedException {
    UUID[] recoveryId = new UUID[1];
    await(() -> {
      List<UUID> ids = jdbc.sql("SELECT recovery_id FROM password_recovery_requests WHERE account_id = :accountId")
          .param("accountId", accountId)
          .query(UUID.class)
          .list();
      if (ids.isEmpty()) {
        return false;
      }
      recoveryId[0] = ids.getFirst();
      return true;
    }, "password recovery request");
    return recoveryId[0];
  }

  private void assertNoDurablePayloadContains(String... secrets) {
    List<String> payloads = jdbc.sql("SELECT payload FROM outbox_messages WHERE message_kind IN ('RESULT', 'EVENT')").query(String.class).list();
    for (String secret : secrets) {
      assertThat(payloads).noneMatch(payload -> payload.contains(secret));
    }
  }

  private static void await(java.util.function.BooleanSupplier condition, String description) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(description + " was not completed within the deadline");
  }
}
