package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountResponse;
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionResponse;
import com.estoma.authentication.contracts.rpc.RevokeSessionRequest;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.session.Session;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class LoginSessionRpcIT extends AbstractPostgresIT {

  private RpcRequestContext context(UUID accountId) {
    return new RpcRequestContext(UUID.randomUUID(), UUID.randomUUID(), "test", accountId == null ? null : new com.estoma.authentication.contracts.Actor("ACCOUNT", accountId, null, "SELF", null), accountId, null);
  }

  private static final String PASSWORD = "correct horse battery staple";

  @Autowired private RpcDispatcher dispatcher;
  @Autowired private AuthenticateAccountHandler authenticateHandler;
  @Autowired private RefreshSessionHandler refreshHandler;
  @Autowired private RevokeSessionHandler revokeHandler;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private PasswordHasher passwordHasher;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private JdbcClient jdbc;

  @Test
  void authenticateSucceedsWithCorrectCredentials() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);

    var outcome =
        dispatcher.execute(
             context(null),
            () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, "Chrome", "127.0.0.1", "UA")));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    var response = (AuthenticateAccountResponse) ((RpcOutcome.Success<?>) outcome).response();
    assertThat(response.accessToken()).isNotBlank();
    assertThat(response.refreshToken()).isNotBlank();
  }

  @Test
  void temporaryCredentialCreatesShortPasswordChangeSessionWithoutRefreshToken() {
    String identifier = "temporary-" + UUID.randomUUID();
    UUID accountId = insertActiveAccountWithPassword(identifier, PASSWORD);
    Instant now = Instant.now();
    Credential temporary = credentialRepository.findByAccountId(accountId).orElseThrow()
        .temporaryPassword(passwordHasher.hash(PASSWORD), now.plusSeconds(60), now);
    credentialRepository.update(temporary);

  var outcome = dispatcher.execute(context(null), () -> authenticateHandler.handle(
        new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));

    var response = (AuthenticateAccountResponse) ((RpcOutcome.Success<?>) outcome).response();
    assertThat(response.refreshToken()).isNull();
    assertThat(response.authState()).isEqualTo(com.estoma.authentication.contracts.rpc.AuthState.PASSWORD_CHANGE_REQUIRED);
    assertThat(sessionRepository.findById(response.sessionId()).orElseThrow().authState().name()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    assertThat(credentialRepository.findByAccountId(accountId).orElseThrow().temporaryPasswordConsumedAt()).isNotNull();
  }

  @Test
  void authenticateFailsWithWrongPasswordAndIncrementsFailedAttempts() {
    String identifier = "user-" + UUID.randomUUID();
    UUID accountId = insertActiveAccountWithPassword(identifier, PASSWORD);

    var outcome =
        dispatcher.execute(
            context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, "wrong-password", null, null, null)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.INVALID_CREDENTIALS);
    assertThat(accountRepository.findById(accountId).orElseThrow().failedAttempts()).isEqualTo(1);
  }

  @Test
  void authenticateDoesNotRevealAnUnknownIdentifier() {
    var outcome =
        dispatcher.execute(
            context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, "no-such-user", "irrelevant", null, null, null)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.INVALID_CREDENTIALS);
  }

  @Test
  void fifthFailedAttemptLocksTheAccount() {
    String identifier = "user-" + UUID.randomUUID();
    UUID accountId = insertActiveAccountWithPassword(identifier, PASSWORD);

    for (int i = 0; i < 5; i++) {
      dispatcher.execute(context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, "wrong", null, null, null)));
    }

    assertThat(accountRepository.findById(accountId).orElseThrow().isLocked(Instant.now())).isTrue();

    var lockedOutcome =
         dispatcher.execute(context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));
    assertThat(((RpcOutcome.Rejected<?>) lockedOutcome).code()).isEqualTo(RejectionCode.ACCOUNT_LOCKED);
  }

  @Test
  void concurrentFailuresLockExactlyAtFiveAttempts() throws Exception {
    String identifier = "user-" + UUID.randomUUID();
    UUID accountId = insertActiveAccountWithPassword(identifier, PASSWORD);
    int callers = 10;
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = java.util.stream.IntStream.range(0, callers)
          .mapToObj(ignored -> executor.submit(() -> {
            ready.countDown();
            start.await();
            return dispatcher.execute(
                 context(null),
                () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, "wrong", null, null, null)));
          }))
          .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }

    Account account = accountRepository.findById(accountId).orElseThrow();
    assertThat(account.failedAttempts()).isEqualTo(5);
    assertThat(account.lockedUntil()).isAfter(Instant.now().plusSeconds(14 * 60));
    assertThat(account.lockedUntil()).isBefore(Instant.now().plusSeconds(16 * 60));
  }

  @Test
  void refreshAcceptsThePreviousTokenWithinTheGracePeriod() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authOutcome =
        (RpcOutcome.Success<?>)
            dispatcher.execute(context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));
    var authResponse = (AuthenticateAccountResponse) authOutcome.response();

    var refreshOutcome =
         dispatcher.execute(context(authResponse.accountId()), () -> refreshHandler.handle(context(authResponse.accountId()), new RefreshSessionRequest(authResponse.sessionId(), authResponse.refreshToken())));

    assertThat(refreshOutcome).isInstanceOf(RpcOutcome.Success.class);
    var refreshResponse = (RefreshSessionResponse) ((RpcOutcome.Success<?>) refreshOutcome).response();
    assertThat(refreshResponse.refreshToken()).isNotEqualTo(authResponse.refreshToken());

    var reuseOldToken = dispatcher.execute(context(authResponse.accountId()), () -> refreshHandler.handle(context(authResponse.accountId()), new RefreshSessionRequest(authResponse.sessionId(), authResponse.refreshToken())));
    assertThat(reuseOldToken).isInstanceOf(RpcOutcome.Success.class);
  }

  @Test
  void refreshPreviousTokenOutsideGraceRevokesSessionAndReportsReplay() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authenticated = (AuthenticateAccountResponse) ((RpcOutcome.Success<?>) dispatcher.execute(
        context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)))).response();
     dispatcher.execute(context(authenticated.accountId()), () -> refreshHandler.handle(context(authenticated.accountId()), new RefreshSessionRequest(authenticated.sessionId(), authenticated.refreshToken())));
    jdbc.sql("UPDATE sessions SET previous_refresh_valid_until = :expired WHERE session_id = :sessionId")
        .param("expired", java.sql.Timestamp.from(Instant.now().minusSeconds(1)))
        .param("sessionId", authenticated.sessionId())
        .update();

    var replay = dispatcher.execute(
        context(authenticated.accountId()), () -> refreshHandler.handle(context(authenticated.accountId()), new RefreshSessionRequest(authenticated.sessionId(), authenticated.refreshToken())));

    assertThat(((RpcOutcome.Rejected<?>) replay).code()).isEqualTo(RejectionCode.REFRESH_TOKEN_REPLAY);
    assertThat(sessionRepository.findById(authenticated.sessionId()).orElseThrow().revocationReason())
         .isEqualTo("REFRESH_TOKEN_REPLAY");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id = :sessionId AND message_type = 'SessionRevoked'")
        .param("sessionId", authenticated.sessionId()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void concurrentExpiredPreviousTokenRefreshRevokesOnlyOnce() throws Exception {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authenticated = (AuthenticateAccountResponse) ((RpcOutcome.Success<?>) dispatcher.execute(
        context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)))).response();
     dispatcher.execute(context(authenticated.accountId()), () -> refreshHandler.handle(context(authenticated.accountId()), new RefreshSessionRequest(authenticated.sessionId(), authenticated.refreshToken())));
    jdbc.sql("UPDATE sessions SET previous_refresh_valid_until = :expired WHERE session_id = :sessionId")
        .param("expired", java.sql.Timestamp.from(Instant.now().minusSeconds(1)))
        .param("sessionId", authenticated.sessionId())
        .update();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> executor.submit(() -> {
        ready.countDown();
        start.await();
         return dispatcher.execute(context(authenticated.accountId()), () -> refreshHandler.handle(context(authenticated.accountId()), new RefreshSessionRequest(authenticated.sessionId(), authenticated.refreshToken())));
      })).toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(futures.stream().map(future -> {
        try {
          return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
          throw new AssertionError(exception);
        }
      })).anyMatch(outcome -> outcome instanceof RpcOutcome.Rejected<?> rejected
          && rejected.code() == RejectionCode.REFRESH_TOKEN_REPLAY);
    }
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id = :sessionId AND message_type = 'SessionRevoked'")
        .param("sessionId", authenticated.sessionId()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void revokeSessionIsIdempotent() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authOutcome =
        (RpcOutcome.Success<?>)
             dispatcher.execute(context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));
    var authResponse = (AuthenticateAccountResponse) authOutcome.response();

     var first = dispatcher.execute(context(authResponse.accountId()), () -> revokeHandler.handle(context(authResponse.accountId()), new RevokeSessionRequest(authResponse.sessionId())));
     var second = dispatcher.execute(context(authResponse.accountId()), () -> revokeHandler.handle(context(authResponse.accountId()), new RevokeSessionRequest(authResponse.sessionId())));

    assertThat(first).isInstanceOf(RpcOutcome.Success.class);
    assertThat(second).isInstanceOf(RpcOutcome.Success.class);
    assertThat(sessionRepository.findById(authResponse.sessionId()).orElseThrow().isValid(Instant.now())).isFalse();
  }

  @Test
  void concurrentRevocationLocksSessionAndEmitsOneEvent() throws Exception {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authResponse = (AuthenticateAccountResponse) ((RpcOutcome.Success<?>) dispatcher.execute(
        context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)))).response();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> executor.submit(() -> {
        ready.countDown();
        start.await();
        RpcRequestContext context = context(authResponse.accountId());
         return dispatcher.execute(context, () -> revokeHandler.handle(context, new RevokeSessionRequest(authResponse.sessionId())));
      })).toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var future : futures) {
        assertThat(future.get(10, TimeUnit.SECONDS)).isInstanceOf(RpcOutcome.Success.class);
      }
    }

    assertThat(sessionRepository.findById(authResponse.sessionId()).orElseThrow().revokedAt()).isNotNull();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id = :sessionId AND message_type = 'SessionRevoked'")
        .param("sessionId", authResponse.sessionId()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void revokeSessionRejectsAnotherAccountsSession() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authOutcome =
        (RpcOutcome.Success<?>)
             dispatcher.execute(context(null), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));
    var authResponse = (AuthenticateAccountResponse) authOutcome.response();

     var outcome = dispatcher.execute(context(authResponse.accountId()), () -> revokeHandler.handle(
         new com.estoma.authentication.contracts.rpc.RpcRequestContext(
             UUID.randomUUID(), UUID.randomUUID(), "bff",
             new com.estoma.authentication.contracts.Actor("ACCOUNT", UUID.randomUUID(), null, "SELF", null),
             authResponse.accountId(), null),
         new RevokeSessionRequest(authResponse.sessionId())));

    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.SESSION_NOT_FOUND);
  }

  private UUID insertActiveAccountWithPassword(String identifier, String password) {
    Account account = Account.createNew(UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.USERNAME, identifier), Instant.now());
    accountRepository.insert(account);
    personStatusProjectionRepository.upsertIfNewer(account.personId(), "ACTIVE", 1, Instant.now());
    credentialRepository.insert(Credential.configure(account.accountId(), passwordHasher.hash(password), Instant.now()));
    Account activated = account.activated(Instant.now());
    accountRepository.update(activated, account.version());
    return account.accountId();
  }
}
