package com.estoma.authentication.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.rpc.AuthenticateAccountHandler;
import com.estoma.authentication.application.rpc.ChangePasswordHandler;
import com.estoma.authentication.application.rpc.RefreshSessionHandler;
import com.estoma.authentication.application.rpc.ResetPasswordHandler;
import com.estoma.authentication.application.rpc.SetTemporaryPasswordHandler;
import com.estoma.authentication.application.rpc.ValidateRecoveryCodeHandler;
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
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionResponse;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
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
 * V1 account lifecycle smoke test. Durable Person/command edges use RabbitMQ;
 * operation-specific security payloads use the dispatcher except for the
 * communications-only recovery delivery RPC, which crosses RabbitMQ too.
 */
class V1AccountLifecycleSmokeIT extends AbstractRabbitMqIT {

  private static final String NORMAL_PASSWORD = "a-normal-password-123";
  private static final String RESET_PASSWORD = "a-reset-password-456";

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private RpcDispatcher dispatcher;
  @Autowired private AccountRepository accountRepository;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private SetTemporaryPasswordHandler setTemporaryPasswordHandler;
  @Autowired private AuthenticateAccountHandler authenticateAccountHandler;
  @Autowired private ChangePasswordHandler changePasswordHandler;
  @Autowired private RefreshSessionHandler refreshSessionHandler;
  @Autowired private ValidateRecoveryCodeHandler validateRecoveryCodeHandler;
  @Autowired private ResetPasswordHandler resetPasswordHandler;
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

    SetTemporaryPasswordResponse temporaryPassword = success(dispatcher.execute(
        UUID.randomUUID(),
        () -> setTemporaryPasswordHandler.handle(new SetTemporaryPasswordRequest(accountId, null, UUID.randomUUID(), "v1-smoke-" + UUID.randomUUID()))));

    // Accounts are intentionally inactive after CreateAccount; activation is the required command once a credential exists.
    publishCommand(new ActivateAccountCommand(accountId, 1));
    await(() -> accountRepository.findById(accountId).orElseThrow().isActive(), "account activation");

    AuthenticateAccountResponse restrictedSession = success(dispatcher.execute(
        UUID.randomUUID(),
        () -> authenticateAccountHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, login, temporaryPassword.temporaryPassword(), null, null, null))));
    assertThat(restrictedSession.mustChangePassword()).isTrue();
    assertThat(restrictedSession.refreshToken()).isNull();
    assertThat(sessionRepository.findById(restrictedSession.sessionId()).orElseThrow().authState())
        .isEqualTo(Session.AuthState.PASSWORD_CHANGE_REQUIRED);

    RpcOutcome<?> restrictedRefresh = dispatcher.execute(
        UUID.randomUUID(), () -> refreshSessionHandler.handle(new RefreshSessionRequest(restrictedSession.sessionId(), "not-issued")));
    assertThat(restrictedRefresh).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) restrictedRefresh).code()).isEqualTo(RejectionCode.INVALID_REFRESH_TOKEN);

    ChangePasswordResponse passwordChanged = success(dispatcher.execute(
        UUID.randomUUID(),
        () -> changePasswordHandler.handle(new ChangePasswordRequest(accountId, restrictedSession.sessionId(), temporaryPassword.temporaryPassword(), NORMAL_PASSWORD))));
    assertThat(sessionRepository.findById(restrictedSession.sessionId()).orElseThrow().authState())
        .isEqualTo(Session.AuthState.NORMAL);
    assertThat(passwordChanged.refreshToken()).isNotBlank();

    RefreshSessionResponse refreshed = success(dispatcher.execute(
        UUID.randomUUID(), () -> refreshSessionHandler.handle(new RefreshSessionRequest(restrictedSession.sessionId(), passwordChanged.refreshToken()))));
    assertThat(refreshed.refreshToken()).isNotBlank();

    publishCommand(new RequestPasswordRecoveryCommand(LoginType.USERNAME, login));
    UUID recoveryId = awaitRecoveryId(accountId);
    String recoveryCode = requestRecoveryCodeAsCommunicationsSystem(recoveryId);

    ValidateRecoveryCodeResponse validation = success(dispatcher.execute(
        UUID.randomUUID(), () -> validateRecoveryCodeHandler.handle(new ValidateRecoveryCodeRequest(LoginType.USERNAME, login, recoveryCode))));
    assertThat(validation.result()).isEqualTo("VALID");

    success(dispatcher.execute(
        UUID.randomUUID(), () -> resetPasswordHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, login, recoveryCode, RESET_PASSWORD))));
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
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    MessageEnvelope request = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY.wireName(), 1, MessageKind.RPC_REQUEST,
        "communications", java.time.Instant.now(), UUID.randomUUID(), null, null, null, null, null,
        new Actor("SYSTEM", null, "communications", "GLOBAL", null),
        codec.toJsonNode(new GetRecoveryCodeForDeliveryRequest(recoveryId)));
    Message reply = rabbitTemplate.sendAndReceive(
        MessagingNames.SECURITY_RPC_EXCHANGE,
        MessagingNames.AUTHENTICATION_RPC_QUEUE,
        new Message(codec.encode(request), properties));

    assertThat(reply).isNotNull();
    MessageEnvelope response = codec.decode(reply.getBody());
    ResultPayload result = codec.convertPayload(response, ResultPayload.class);
    assertThat(result.status()).isEqualTo(ResultStatus.SUCCEEDED);
    return codec.toJsonNode(result.data()).get("code").asString();
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
    List<String> payloads = jdbc.sql("SELECT payload FROM outbox_messages").query(String.class).list();
    for (String secret : secrets) {
      assertThat(payloads).noneMatch(payload -> payload.contains(secret));
    }
  }

  private static <T> T success(RpcOutcome<T> outcome) {
    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    @SuppressWarnings("unchecked")
    T response = ((RpcOutcome.Success<T>) outcome).response();
    return response;
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
