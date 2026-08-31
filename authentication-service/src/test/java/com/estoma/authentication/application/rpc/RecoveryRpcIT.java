package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.result.ResultStatus;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountResponse;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.ChangePasswordResponse;
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionResponse;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import com.estoma.authentication.domain.session.Session;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.messaging.rpc.RpcListener;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class RecoveryRpcIT extends AbstractPostgresIT {

  private RpcRequestContext context(UUID accountId) {
    return new RpcRequestContext(UUID.randomUUID(), UUID.randomUUID(), "test", new com.estoma.authentication.contracts.Actor("ACCOUNT", accountId, null, "SELF", null), accountId, null);
  }

  private static final String OLD_PASSWORD = "old-password-123";
  private static final String NEW_PASSWORD = "new-password-456";

  @Autowired private RpcDispatcher dispatcher;
  @Autowired private ValidateRecoveryCodeHandler validateHandler;
  @Autowired private RecoveryCodeDeriver codeDeriver;
  @Autowired private ResetPasswordHandler resetHandler;
  @Autowired private ChangePasswordHandler changeHandler;
  @Autowired private AuthenticateAccountHandler authenticateHandler;
  @Autowired private RefreshSessionHandler refreshHandler;
  @Autowired private RpcListener rpcListener;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;
  @Autowired private PasswordRecoveryRepository recoveryRepository;
  @Autowired private PasswordHasher passwordHasher;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcClient jdbc;

  @MockitoSpyBean private SessionRepository sessionRepositorySpy;

  @Test
  void validateRecoveryCodeAcceptsTheCorrectCode() {
    UUID accountId = insertActiveAccountWithPassword();
    var recovery = insertRecoveryRequest(accountId);
    String code = codeDeriver.derive(recovery.recoveryId(), recovery.codeDerivationContext());

    var outcome = dispatcher.execute(context(accountId), () -> validateHandler.handle(new ValidateRecoveryCodeRequest(LoginType.USERNAME, accountLogin(accountId), code)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(((com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse) ((RpcOutcome.Success<?>) outcome).response()).result()).isEqualTo("VALID");
  }

  @Test
  void validateRecoveryCodeRejectsAWrongCodeWithoutThrowing() {
    UUID accountId = insertActiveAccountWithPassword();
    var recovery = insertRecoveryRequest(accountId);
    String wrongCode = wrongCodeFor(codeDeriver.derive(recovery.recoveryId(), recovery.codeDerivationContext()));

    var outcome = dispatcher.execute(context(accountId), () -> validateHandler.handle(new ValidateRecoveryCodeRequest(LoginType.USERNAME, accountLogin(accountId), wrongCode)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(((com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse) ((RpcOutcome.Success<?>) outcome).response()).result()).isEqualTo("INVALID");
  }

  @Test
  void fifthInvalidRecoveryCodeExhaustsTheRequest() {
    UUID accountId = insertActiveAccountWithPassword();
    PasswordRecoveryRequest recovery = insertRecoveryRequest(accountId);
    String wrongCode = wrongCodeFor(codeDeriver.derive(recovery.recoveryId(), recovery.codeDerivationContext()));

    for (int attempt = 0; attempt < 5; attempt++) {
      var outcome = dispatcher.execute(
          context(accountId),
          () -> validateHandler.handle(new ValidateRecoveryCodeRequest(LoginType.USERNAME, accountLogin(accountId), wrongCode)));
      assertThat(((com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse) ((RpcOutcome.Success<?>) outcome).response()).result())
          .isEqualTo("INVALID");
    }

    PasswordRecoveryRequest exhausted = recoveryRepository.findById(recovery.recoveryId()).orElseThrow();
    assertThat(exhausted.failedAttempts()).isEqualTo(5);
    assertThat(exhausted.status()).isEqualTo(PasswordRecoveryRequest.Status.ATTEMPTS_EXHAUSTED);
  }

  @Test
  void resetPasswordChangesTheCredentialAndConsumesTheRequest() {
    UUID accountId = insertActiveAccountWithPassword();
    var recovery = insertRecoveryRequest(accountId);
    String code = codeDeriver.derive(recovery.recoveryId(), recovery.codeDerivationContext());

    var outcome = dispatcher.execute(context(accountId), () -> resetHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, accountLogin(accountId), code, NEW_PASSWORD)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(passwordHasher.matches(NEW_PASSWORD, credentialRepository.findByAccountId(accountId).orElseThrow().hash())).isTrue();
    assertThat(recoveryRepository.findById(recovery.recoveryId()).orElseThrow().isUsed()).isTrue();
  }

  @Test
  void resetPasswordCannotBeRepeatedWithTheSameCode() {
    UUID accountId = insertActiveAccountWithPassword();
    var recovery = insertRecoveryRequest(accountId);
    String code = codeDeriver.derive(recovery.recoveryId(), recovery.codeDerivationContext());
    dispatcher.execute(context(accountId), () -> resetHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, accountLogin(accountId), code, NEW_PASSWORD)));

    var second = dispatcher.execute(context(accountId), () -> resetHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, accountLogin(accountId), code, "yet-another-password")));

    assertThat(((RpcOutcome.Rejected<?>) second).code()).isEqualTo(RejectionCode.RECOVERY_NOT_AVAILABLE);
  }

  @Test
  void resetPasswordRejectsAnExpiredRequest() {
    UUID accountId = insertActiveAccountWithPassword();
    Instant past = Instant.now().minusSeconds(60);
    var recovery = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, past.minusSeconds(1800), past);
    recoveryRepository.insert(recovery);
    String code = codeDeriver.derive(recovery.recoveryId(), recovery.codeDerivationContext());

    var outcome = dispatcher.execute(context(accountId), () -> resetHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, accountLogin(accountId), code, NEW_PASSWORD)));

    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.RECOVERY_NOT_AVAILABLE);
  }

  @Test
  void changePasswordSucceedsWithCorrectCurrentPassword() {
    UUID accountId = insertActiveAccountWithPassword();
    Session currentSession = normalSession(accountId);
    sessionRepository.insert(currentSession);

     var outcome = dispatcher.execute(context(accountId), () -> changeHandler.handle(context(accountId), new ChangePasswordRequest(currentSession.sessionId(), OLD_PASSWORD, NEW_PASSWORD)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(passwordHasher.matches(NEW_PASSWORD, credentialRepository.findByAccountId(accountId).orElseThrow().hash())).isTrue();
    assertThat(sessionRepository.findById(currentSession.sessionId()).orElseThrow().isValid(Instant.now())).isTrue();
     assertThat(eventCount(accountId, "OtherAccountSessionsRevoked")).isZero();
   }

  @Test
  void changePasswordRotatesUsableCredentialsAndInvalidatesThePreviousRefreshToken() {
    UUID accountId = insertActiveAccountWithPassword();
    AuthenticateAccountResponse authenticated = authenticate(accountId, OLD_PASSWORD);
    Session otherSession = normalSession(accountId);
    sessionRepository.insert(otherSession);

    var changedOutcome = dispatcher.execute(
        context(accountId),
        () -> changeHandler.handle(
            context(accountId),
            new ChangePasswordRequest(authenticated.sessionId(), OLD_PASSWORD, NEW_PASSWORD)));

    assertThat(changedOutcome).isInstanceOf(RpcOutcome.Success.class);
    ChangePasswordResponse changed = (ChangePasswordResponse) ((RpcOutcome.Success<?>) changedOutcome).response();
    assertThat(changed.sessionId()).isEqualTo(authenticated.sessionId());
    assertThat(changed.accessToken()).isNotBlank().isNotEqualTo(authenticated.accessToken());
    assertThat(changed.refreshToken()).isNotBlank().isNotEqualTo(authenticated.refreshToken());
    assertThat(sessionRepository.findById(authenticated.sessionId()).orElseThrow().isValid(Instant.now())).isTrue();
    assertThat(sessionRepository.findById(otherSession.sessionId()).orElseThrow().isValid(Instant.now())).isFalse();

    var refreshedOutcome = dispatcher.execute(
        context(accountId),
        () -> refreshHandler.handle(
            context(accountId), new RefreshSessionRequest(changed.sessionId(), changed.refreshToken())));
    assertThat(refreshedOutcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(((RefreshSessionResponse) ((RpcOutcome.Success<?>) refreshedOutcome).response()).refreshToken())
        .isNotEqualTo(changed.refreshToken());

    jdbc.sql("UPDATE sessions SET previous_refresh_valid_until = :expired WHERE session_id = :sessionId")
        .param("expired", java.sql.Timestamp.from(Instant.now().minusSeconds(1)))
        .param("sessionId", authenticated.sessionId())
        .update();
    var previousToken = dispatcher.execute(
        context(accountId),
        () -> refreshHandler.handle(
            context(accountId), new RefreshSessionRequest(changed.sessionId(), changed.refreshToken())));
    assertThat(((RpcOutcome.Rejected<?>) previousToken).code()).isEqualTo(RejectionCode.REFRESH_TOKEN_REPLAY);

    assertThat(authenticate(accountId, NEW_PASSWORD).sessionId()).isNotEqualTo(authenticated.sessionId());
    var oldPassword = dispatcher.execute(
        context(null),
        () -> authenticateHandler.handle(
            new AuthenticateAccountRequest(LoginType.USERNAME, accountLogin(accountId), OLD_PASSWORD, null, null, null)));
    assertThat(((RpcOutcome.Rejected<?>) oldPassword).code()).isEqualTo(RejectionCode.INVALID_CREDENTIALS);
  }

  @Test
  void securityRpcEventsPreserveRequestMetadata() {
    UUID accountId = insertActiveAccountWithPassword();
    Session currentSession = normalSession(accountId);
    sessionRepository.insert(currentSession);
    UUID messageId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    RpcRequestContext requestContext = new RpcRequestContext(
        messageId,
        correlationId,
        "bff",
        new com.estoma.authentication.contracts.Actor("ACCOUNT", accountId, null, "SELF", null),
        accountId,
        null);

    dispatcher.execute(
        requestContext,
        () -> changeHandler.handle(requestContext, new ChangePasswordRequest(currentSession.sessionId(), OLD_PASSWORD, NEW_PASSWORD)));

    var metadata = jdbc.sql(
            "SELECT correlation_id, causation_id, operation_id, idempotency_key, actor::jsonb ->> 'accountId' "
                + "FROM outbox_messages WHERE message_type = 'PasswordChanged' AND correlation_id = :correlationId")
        .param("correlationId", correlationId)
        .query((rs, rowNum) -> new Object[] {
            rs.getObject(1), rs.getObject(2), rs.getObject(3), rs.getString(4), rs.getString(5)})
        .single();

    assertThat(metadata[0]).isEqualTo(correlationId);
    assertThat(metadata[1]).isEqualTo(messageId);
    assertThat(metadata[2]).isNull();
    assertThat(metadata[3]).isNull();
    assertThat(metadata[4]).isEqualTo(accountId.toString());
  }

  @Test
  void changePasswordRevokesOtherSessionsAndEmitsOtherSessionsRevoked() {
    UUID accountId = insertActiveAccountWithPassword();
    Session currentSession = normalSession(accountId);
    Session otherSession = normalSession(accountId);
    sessionRepository.insert(currentSession);
    sessionRepository.insert(otherSession);

     var outcome = dispatcher.execute(context(accountId), () -> changeHandler.handle(context(accountId), new ChangePasswordRequest(currentSession.sessionId(), OLD_PASSWORD, NEW_PASSWORD)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(sessionRepository.findById(currentSession.sessionId()).orElseThrow().isValid(Instant.now())).isTrue();
    assertThat(sessionRepository.findById(otherSession.sessionId()).orElseThrow().isValid(Instant.now())).isFalse();
    assertThat(eventCount(accountId, "OtherAccountSessionsRevoked")).isEqualTo(1);
    assertThat(eventCount(accountId, "AllAccountSessionsRevoked")).isZero();
  }

  @Test
  void changePasswordRejectsWrongCurrentPassword() {
    UUID accountId = insertActiveAccountWithPassword();
    Session currentSession = normalSession(accountId);
    sessionRepository.insert(currentSession);

     var outcome = dispatcher.execute(context(accountId), () -> changeHandler.handle(context(accountId), new ChangePasswordRequest(currentSession.sessionId(), "not-the-current-password", NEW_PASSWORD)));

    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.INVALID_CREDENTIALS);
  }

  @Test
  void changePasswordRejectionsLeaveCredentialSessionAndEventsUntouched() {
    UUID accountId = insertActiveAccountWithPassword();
    Session currentSession = normalSession(accountId);
    sessionRepository.insert(currentSession);
    var originalHash = credentialRepository.findByAccountId(accountId).orElseThrow().hash();
    String originalRefreshHash = sessionRepository.findById(currentSession.sessionId()).orElseThrow().refreshTokenHash();

    var wrongCurrent = dispatcher.execute(
        context(accountId),
        () -> changeHandler.handle(
            context(accountId),
            new ChangePasswordRequest(currentSession.sessionId(), "not-the-current-password", NEW_PASSWORD)));
    assertThat(((RpcOutcome.Rejected<?>) wrongCurrent).code()).isEqualTo(RejectionCode.INVALID_CREDENTIALS);

    var invalidPolicy = dispatcher.execute(
        context(accountId),
        () -> changeHandler.handle(
            context(accountId), new ChangePasswordRequest(currentSession.sessionId(), OLD_PASSWORD, "too-short")));
    assertThat(((RpcOutcome.Rejected<?>) invalidPolicy).code()).isEqualTo(RejectionCode.INVALID_PASSWORD);

    assertThat(credentialRepository.findByAccountId(accountId).orElseThrow().hash()).isEqualTo(originalHash);
    Session persisted = sessionRepository.findById(currentSession.sessionId()).orElseThrow();
    assertThat(persisted.refreshTokenHash()).isEqualTo(originalRefreshHash);
    assertThat(persisted.isValid(Instant.now())).isTrue();
    assertThat(eventCount(accountId, "PasswordChanged")).isZero();
    assertThat(eventCount(accountId, "OtherAccountSessionsRevoked")).isZero();
  }

  @Test
  void refreshAndChangePasswordSerializeAndTheStaleRefreshTokenCannotOverwriteTheChange() throws Exception {
    UUID accountId = insertActiveAccountWithPassword();
    AuthenticateAccountResponse authenticated = authenticate(accountId, OLD_PASSWORD);
    CountDownLatch refreshLockAcquired = new CountDownLatch(1);
    CountDownLatch releaseRefresh = new CountDownLatch(1);
    AtomicInteger lockCalls = new AtomicInteger();
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Optional<Session> locked = (Optional<Session>) invocation.callRealMethod();
      if (lockCalls.incrementAndGet() == 1) {
        refreshLockAcquired.countDown();
        await(releaseRefresh);
      }
      return locked;
    }).when(sessionRepositorySpy).findByIdForUpdate(authenticated.sessionId());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var refresh = executor.submit(() -> dispatcher.execute(
          context(accountId),
          () -> refreshHandler.handle(
              context(accountId), new RefreshSessionRequest(authenticated.sessionId(), authenticated.refreshToken()))));
      await(refreshLockAcquired);
      var change = executor.submit(() -> dispatcher.execute(
          context(accountId),
          () -> changeHandler.handle(
              context(accountId),
              new ChangePasswordRequest(authenticated.sessionId(), OLD_PASSWORD, NEW_PASSWORD))));

      releaseRefresh.countDown();
      var refreshOutcome = refresh.get(10, TimeUnit.SECONDS);
      var changeOutcome = change.get(10, TimeUnit.SECONDS);
      assertThat(refreshOutcome).isInstanceOf(RpcOutcome.Success.class);
      assertThat(changeOutcome).isInstanceOf(RpcOutcome.Success.class);
      String staleRefreshToken =
          ((RefreshSessionResponse) ((RpcOutcome.Success<?>) refreshOutcome).response()).refreshToken();
      ChangePasswordResponse changed =
          (ChangePasswordResponse) ((RpcOutcome.Success<?>) changeOutcome).response();

      var staleRefresh = dispatcher.execute(
          context(accountId),
          () -> refreshHandler.handle(
              context(accountId), new RefreshSessionRequest(authenticated.sessionId(), staleRefreshToken)));
      assertThat(((RpcOutcome.Rejected<?>) staleRefresh).code()).isEqualTo(RejectionCode.INVALID_REFRESH_TOKEN);
      var changedRefresh = dispatcher.execute(
          context(accountId),
          () -> refreshHandler.handle(
              context(accountId), new RefreshSessionRequest(authenticated.sessionId(), changed.refreshToken())));
      assertThat(changedRefresh).isInstanceOf(RpcOutcome.Success.class);
    }

    assertThat(passwordHasher.matches(NEW_PASSWORD, credentialRepository.findByAccountId(accountId).orElseThrow().hash())).isTrue();
  }

  @Test
  void technicalFailureAfterCredentialMutationRollsBackThePasswordAndSessionUpdate() {
    UUID accountId = insertActiveAccountWithPassword();
    Session currentSession = normalSession(accountId);
    sessionRepository.insert(currentSession);
    var originalHash = credentialRepository.findByAccountId(accountId).orElseThrow().hash();
    String originalRefreshHash = sessionRepository.findById(currentSession.sessionId()).orElseThrow().refreshTokenHash();
    doThrow(new IllegalStateException("forced session persistence failure"))
        .when(sessionRepositorySpy)
        .update(any(Session.class));

    assertThatThrownBy(() -> dispatcher.execute(
        context(accountId),
        () -> changeHandler.handle(
            context(accountId), new ChangePasswordRequest(currentSession.sessionId(), OLD_PASSWORD, NEW_PASSWORD))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("forced session persistence failure");

    assertThat(credentialRepository.findByAccountId(accountId).orElseThrow().hash()).isEqualTo(originalHash);
    Session persisted = sessionRepository.findById(currentSession.sessionId()).orElseThrow();
    assertThat(persisted.refreshTokenHash()).isEqualTo(originalRefreshHash);
    assertThat(persisted.isValid(Instant.now())).isTrue();
    assertThat(eventCount(accountId, "PasswordChanged")).isZero();
  }

  @Test
  void repeatedEphemeralTransportDeliveryCannotRepeatThePasswordMutation() {
    UUID accountId = insertActiveAccountWithPassword();
    AuthenticateAccountResponse authenticated = authenticate(accountId, OLD_PASSWORD);
    MessageEnvelope request = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.CHANGE_PASSWORD.wireName(), 1, MessageKind.RPC_REQUEST,
        "bff", Instant.now(), UUID.randomUUID(), null, null, null, accountId, null,
        new Actor("ACCOUNT", accountId, null, "SELF", null),
        codec.toJsonNode(new ChangePasswordRequest(authenticated.sessionId(), OLD_PASSWORD, NEW_PASSWORD)));
    MessageProperties properties = new MessageProperties();
    properties.setReplyTo("test-reply");

    ResultPayload first = resultOf(rpcListener.onMessage(new Message(codec.encode(request), properties)));
    ResultPayload replay = resultOf(rpcListener.onMessage(new Message(codec.encode(request), properties)));

    assertThat(first.status()).isEqualTo(ResultStatus.SUCCEEDED);
    assertThat(replay.status()).isEqualTo(ResultStatus.REJECTED);
    assertThat(replay.error().code()).isEqualTo(RejectionCode.PASSWORD_REUSE.name());
    assertThat(eventCount(accountId, "PasswordChanged")).isEqualTo(1);
    assertThat(passwordHasher.matches(NEW_PASSWORD, credentialRepository.findByAccountId(accountId).orElseThrow().hash())).isTrue();
  }

  @Test
  void changePasswordRejectsReusingTheSamePassword() {
    UUID accountId = insertActiveAccountWithPassword();
    Session currentSession = normalSession(accountId);
    sessionRepository.insert(currentSession);

     var outcome = dispatcher.execute(context(accountId), () -> changeHandler.handle(context(accountId), new ChangePasswordRequest(currentSession.sessionId(), OLD_PASSWORD, OLD_PASSWORD)));

     assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.PASSWORD_REUSE);
  }

  @Test
  void restrictedSessionDoesNotRequireCurrentPasswordAndRemainsUsableAfterInvalidNewPassword() {
    UUID accountId = insertActiveAccountWithPassword();
    Instant now = Instant.now();
    Session restricted = Session.createPasswordChangeRequired(
        UUID.randomUUID(), accountId, null, null, null, now, now.plusSeconds(900));
    sessionRepository.insert(restricted);
    Session persistedRestricted = sessionRepository.findById(restricted.sessionId()).orElseThrow();

    var invalid = dispatcher.execute(
         context(accountId), () -> changeHandler.handle(context(accountId), new ChangePasswordRequest(restricted.sessionId(), null, "passwordpassword")));

    assertThat(((RpcOutcome.Rejected<?>) invalid).code()).isEqualTo(RejectionCode.INVALID_PASSWORD);
    assertThat(sessionRepository.findById(restricted.sessionId()).orElseThrow().authState())
        .isEqualTo(Session.AuthState.PASSWORD_CHANGE_REQUIRED);

    var changed = dispatcher.execute(
         context(accountId), () -> changeHandler.handle(context(accountId), new ChangePasswordRequest(restricted.sessionId(), null, NEW_PASSWORD)));

    assertThat(changed).isInstanceOf(RpcOutcome.Success.class);
    Session renewed = sessionRepository.findById(restricted.sessionId()).orElseThrow();
    assertThat(renewed.authState()).isEqualTo(Session.AuthState.NORMAL);
    assertThat(renewed.refreshTokenHash()).isNotNull();
    assertThat(renewed.previousRefreshTokenHash()).isNull();
    assertThat(renewed.absoluteExpiresAt()).isEqualTo(
        persistedRestricted.createdAt().plusSeconds(30 * 24 * 60 * 60));
  }

  private UUID insertActiveAccountWithPassword() {
    Account account = Account.createNew(UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.USERNAME, "recovery-" + UUID.randomUUID()), Instant.now());
    accountRepository.insert(account);
    personStatusProjectionRepository.upsertIfNewer(account.personId(), "ACTIVE", 1, Instant.now());
    credentialRepository.insert(Credential.configure(account.accountId(), passwordHasher.hash(OLD_PASSWORD), Instant.now()));
    Account activated = account.activated(Instant.now());
    accountRepository.update(activated, account.version());
    return account.accountId();
  }

  private PasswordRecoveryRequest insertRecoveryRequest(UUID accountId) {
    PasswordRecoveryRequest request = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, Instant.now(), Instant.now().plusSeconds(1800));
    recoveryRepository.insert(request);
    return request;
  }

  private String accountLogin(UUID accountId) {
    return accountRepository.findById(accountId).orElseThrow().loginIdentifier().value();
  }

  private String wrongCodeFor(String validCode) {
    char replacement = validCode.charAt(0) == '0' ? '1' : '0';
    return replacement + validCode.substring(1);
  }

  private long eventCount(UUID accountId, String messageType) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id = :accountId AND message_type = :messageType")
        .param("accountId", accountId)
        .param("messageType", messageType)
        .query(Long.class)
        .single();
  }

  private AuthenticateAccountResponse authenticate(UUID accountId, String password) {
    var outcome = dispatcher.execute(
        context(null),
        () -> authenticateHandler.handle(
            new AuthenticateAccountRequest(LoginType.USERNAME, accountLogin(accountId), password, null, null, null)));
    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    return (AuthenticateAccountResponse) ((RpcOutcome.Success<?>) outcome).response();
  }

  private ResultPayload resultOf(Message response) {
    return codec.convertPayload(codec.decode(response.getBody()), ResultPayload.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("concurrency latch").isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static Session normalSession(UUID accountId) {
    Instant now = Instant.now();
    return Session.createNormal(UUID.randomUUID(), accountId, "hash-" + UUID.randomUUID(), null, null, null, now, now.plusSeconds(900), now.plusSeconds(1800));
  }
}
