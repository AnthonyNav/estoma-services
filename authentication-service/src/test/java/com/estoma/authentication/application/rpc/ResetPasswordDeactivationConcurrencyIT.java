package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import com.estoma.authentication.application.CommandDispatcher;
import com.estoma.authentication.application.CommandOutcome;
import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.command.DeactivateAccountCommand;
import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.result.ResultStatus;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.ResetPasswordResponse;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import com.estoma.authentication.domain.session.Session;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.messaging.rpc.RpcListener;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import com.estoma.authentication.testsupport.Envelopes;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ResetPasswordDeactivationConcurrencyIT extends AbstractPostgresIT {

  private static final String OLD_PASSWORD = "old-password-123";
  private static final String NEW_PASSWORD = "new-password-456";

  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;
  @Autowired private PasswordRecoveryRepository recoveryRepository;
  @Autowired private PasswordHasher passwordHasher;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private ResetPasswordHandler resetHandler;
  @Autowired private RecoveryCodeDeriver codeDeriver;
  @Autowired private RpcDispatcher rpcDispatcher;
  @Autowired private CommandDispatcher commandDispatcher;
  @Autowired private RpcListener rpcListener;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private JdbcClient jdbc;

  @MockitoSpyBean private AccountRepository accountRepositorySpy;

  private CountDownLatch resetLockAcquired;
  private CountDownLatch resetCanCommit;
  private CountDownLatch deactivationUpdateEntered;
  private CountDownLatch deactivationCanCommit;

  @BeforeEach
  void resetLatches() {
    resetLockAcquired = new CountDownLatch(1);
    resetCanCommit = new CountDownLatch(1);
    deactivationUpdateEntered = new CountDownLatch(1);
    deactivationCanCommit = new CountDownLatch(1);
  }

  @Test
  void resetLocksAccountBeforeDeactivateAndDeactivateCommitsAfterReset() throws Exception {
    Fixture fixture = fixture();
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Optional<Account> result = (Optional<Account>) invocation.callRealMethod();
      resetLockAcquired.countDown();
      await(resetCanCommit);
      return result;
    }).when(accountRepositorySpy).findByLoginIdentifierForUpdate(any(), any());
    doAnswer(invocation -> {
      deactivationUpdateEntered.countDown();
      return invocation.callRealMethod();
    }).when(accountRepositorySpy).update(any(Account.class), anyInt());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<RpcOutcome<ResetPasswordResponse>> reset = executor.submit(() -> reset(fixture));
      await(resetLockAcquired);

      Future<CommandOutcome> deactivate = executor.submit(() -> deactivate(fixture));
      await(deactivationUpdateEntered);
      assertThat(accountRepository.findById(fixture.accountId()).orElseThrow().isActive()).isTrue();

      resetCanCommit.countDown();
      assertThat(reset.get(10, TimeUnit.SECONDS)).isInstanceOf(RpcOutcome.Success.class);
      assertThat(deactivate.get(10, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Succeeded.class);
    }

    assertThat(accountRepository.findById(fixture.accountId()).orElseThrow().isActive()).isFalse();
    assertThat(passwordHasher.matches(NEW_PASSWORD, credentialRepository.findByAccountId(fixture.accountId()).orElseThrow().hash())).isTrue();
    assertThat(recoveryRepository.findById(fixture.recovery().recoveryId()).orElseThrow().isUsed()).isTrue();
  }

  @Test
  void deactivateCommitsFirstAndResetSeesInactiveAccountWithoutChangingRecoveryCredentialOrSession() throws Exception {
    Fixture fixture = fixture();
    doAnswer(invocation -> {
      Account updated = invocation.getArgument(0);
      Object result = invocation.callRealMethod();
      if (!updated.isActive()) {
        deactivationUpdateEntered.countDown();
        await(deactivationCanCommit);
      }
      return result;
    }).when(accountRepositorySpy).update(any(Account.class), anyInt());
    CountDownLatch resetAttempted = new CountDownLatch(1);
    doAnswer(invocation -> {
      resetAttempted.countDown();
      return invocation.callRealMethod();
    }).when(accountRepositorySpy).findByLoginIdentifierForUpdate(any(), any());

    var originalHash = credentialRepository.findByAccountId(fixture.accountId()).orElseThrow().hash();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<CommandOutcome> deactivate = executor.submit(() -> deactivate(fixture));
      await(deactivationUpdateEntered);

      Future<RpcOutcome<ResetPasswordResponse>> reset = executor.submit(() -> reset(fixture));
      await(resetAttempted);
      deactivationCanCommit.countDown();

      assertThat(deactivate.get(10, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Succeeded.class);
      assertThat(((RpcOutcome.Rejected<?>) reset.get(10, TimeUnit.SECONDS)).code())
          .isEqualTo(RejectionCode.RECOVERY_NOT_AVAILABLE);
    }

    assertThat(accountRepository.findById(fixture.accountId()).orElseThrow().isActive()).isFalse();
    assertThat(credentialRepository.findByAccountId(fixture.accountId()).orElseThrow().hash()).isEqualTo(originalHash);
    assertThat(recoveryRepository.findById(fixture.recovery().recoveryId()).orElseThrow().isUsed()).isFalse();
    assertThat(sessionRepository.findById(fixture.session().sessionId()).orElseThrow().revocationReason())
        .isEqualTo("ACCOUNT_DEACTIVATED");
  }

  @Test
  void resetZeroRowRollsBackDatabaseChangesAndRpcTransportReturnsTechnicalFailure() {
    Fixture fixture = fixture();
    var originalHash = credentialRepository.findByAccountId(fixture.accountId()).orElseThrow().hash();
    doAnswer(invocation -> 0).when(accountRepositorySpy).update(any(Account.class), anyInt());

    MessageEnvelope request = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.RESET_PASSWORD.wireName(), 1, MessageKind.RPC_REQUEST,
        "bff", Instant.now(), UUID.randomUUID(), null, null, null, null, null,
        new Actor("ANONYMOUS", null, null, "PUBLIC", null),
        codec.toJsonNode(new ResetPasswordRequest(LoginType.USERNAME, fixture.login(), fixture.code(), NEW_PASSWORD)));
    MessageProperties properties = new MessageProperties();
    properties.setReplyTo("test-reply");

    Message response = rpcListener.onMessage(new Message(codec.encode(request), properties));
    ResultPayload result = codec.convertPayload(codec.decode(response.getBody()), ResultPayload.class);

    assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
    assertThat(result.error().code()).isEqualTo("TECHNICAL_FAILURE");
    assertThat(credentialRepository.findByAccountId(fixture.accountId()).orElseThrow().hash()).isEqualTo(originalHash);
    assertThat(recoveryRepository.findById(fixture.recovery().recoveryId()).orElseThrow().isUsed()).isFalse();
    assertThat(sessionRepository.findById(fixture.session().sessionId()).orElseThrow().isValid(Instant.now())).isTrue();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id = :accountId AND message_type IN ('PasswordChanged', 'AllAccountSessionsRevoked')")
        .param("accountId", fixture.accountId()).query(Long.class).single()).isZero();
  }

  private RpcOutcome<ResetPasswordResponse> reset(Fixture fixture) {
    return rpcDispatcher.execute(
        new RpcRequestContext(UUID.randomUUID(), UUID.randomUUID(), "test", new Actor("ACCOUNT", fixture.accountId(), null, "SELF", null), fixture.accountId(), null),
        () -> resetHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, fixture.login(), fixture.code(), NEW_PASSWORD)));
  }

  private CommandOutcome deactivate(Fixture fixture) {
    return commandDispatcher.dispatch(Envelopes.command(
        AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName(),
        new DeactivateAccountCommand(fixture.accountId(), 2, "ADMINISTRATIVE", "concurrency test")));
  }

  private Fixture fixture() {
    UUID accountId = UUID.randomUUID();
    Account account = Account.createNew(accountId, UUID.randomUUID(), LoginIdentifier.of(LoginType.USERNAME, "race-" + accountId), Instant.now());
    accountRepository.insert(account);
    personStatusProjectionRepository.upsertIfNewer(account.personId(), "ACTIVE", 1, Instant.now());
    credentialRepository.insert(Credential.configure(accountId, passwordHasher.hash(OLD_PASSWORD), Instant.now()));
    accountRepository.update(account.activated(Instant.now()), 1);
    PasswordRecoveryRequest recovery = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, Instant.now(), Instant.now().plusSeconds(1800));
    recoveryRepository.insert(recovery);
    String code = codeDeriver.derive(recovery.recoveryId(), recovery.codeDerivationContext());
    Session session = Session.createNormal(UUID.randomUUID(), accountId, "session-token-" + accountId, null, null, null, Instant.now(), Instant.now().plusSeconds(900), Instant.now().plusSeconds(1800));
    sessionRepository.insert(session);
    return new Fixture(accountId, account.loginIdentifier().value(), code, recovery, session);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("concurrency latch").isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private record Fixture(UUID accountId, String login, String code, PasswordRecoveryRequest recovery, Session session) {}
}
