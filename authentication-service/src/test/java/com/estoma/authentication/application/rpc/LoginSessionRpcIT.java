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
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LoginSessionRpcIT extends AbstractPostgresIT {

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

  @Test
  void authenticateSucceedsWithCorrectCredentials() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);

    var outcome =
        dispatcher.execute(
            UUID.randomUUID(),
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

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> authenticateHandler.handle(
        new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));

    var response = (AuthenticateAccountResponse) ((RpcOutcome.Success<?>) outcome).response();
    assertThat(response.refreshToken()).isNull();
    assertThat(response.mustChangePassword()).isTrue();
    assertThat(sessionRepository.findById(response.sessionId()).orElseThrow().authState().name()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    assertThat(credentialRepository.findByAccountId(accountId).orElseThrow().temporaryPasswordConsumedAt()).isNotNull();
  }

  @Test
  void authenticateFailsWithWrongPasswordAndIncrementsFailedAttempts() {
    String identifier = "user-" + UUID.randomUUID();
    UUID accountId = insertActiveAccountWithPassword(identifier, PASSWORD);

    var outcome =
        dispatcher.execute(
            UUID.randomUUID(), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, "wrong-password", null, null, null)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.INVALID_CREDENTIALS);
    assertThat(accountRepository.findById(accountId).orElseThrow().failedAttempts()).isEqualTo(1);
  }

  @Test
  void authenticateDoesNotRevealAnUnknownIdentifier() {
    var outcome =
        dispatcher.execute(
            UUID.randomUUID(), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, "no-such-user", "irrelevant", null, null, null)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.INVALID_CREDENTIALS);
  }

  @Test
  void fifthFailedAttemptLocksTheAccount() {
    String identifier = "user-" + UUID.randomUUID();
    UUID accountId = insertActiveAccountWithPassword(identifier, PASSWORD);

    for (int i = 0; i < 5; i++) {
      dispatcher.execute(UUID.randomUUID(), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, "wrong", null, null, null)));
    }

    assertThat(accountRepository.findById(accountId).orElseThrow().isLocked(Instant.now())).isTrue();

    var lockedOutcome =
        dispatcher.execute(UUID.randomUUID(), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));
    assertThat(((RpcOutcome.Rejected<?>) lockedOutcome).code()).isEqualTo(RejectionCode.ACCOUNT_LOCKED);
  }

  @Test
  void refreshAcceptsThePreviousTokenWithinTheGracePeriod() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authOutcome =
        (RpcOutcome.Success<?>)
            dispatcher.execute(UUID.randomUUID(), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));
    var authResponse = (AuthenticateAccountResponse) authOutcome.response();

    var refreshOutcome =
        dispatcher.execute(UUID.randomUUID(), () -> refreshHandler.handle(new RefreshSessionRequest(authResponse.sessionId(), authResponse.refreshToken())));

    assertThat(refreshOutcome).isInstanceOf(RpcOutcome.Success.class);
    var refreshResponse = (RefreshSessionResponse) ((RpcOutcome.Success<?>) refreshOutcome).response();
    assertThat(refreshResponse.refreshToken()).isNotEqualTo(authResponse.refreshToken());

    var reuseOldToken = dispatcher.execute(UUID.randomUUID(), () -> refreshHandler.handle(new RefreshSessionRequest(authResponse.sessionId(), authResponse.refreshToken())));
    assertThat(reuseOldToken).isInstanceOf(RpcOutcome.Success.class);
  }

  @Test
  void revokeSessionIsIdempotent() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authOutcome =
        (RpcOutcome.Success<?>)
            dispatcher.execute(UUID.randomUUID(), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));
    var authResponse = (AuthenticateAccountResponse) authOutcome.response();

    var first = dispatcher.execute(UUID.randomUUID(), () -> revokeHandler.handle(new RevokeSessionRequest(authResponse.sessionId(), authResponse.accountId())));
    var second = dispatcher.execute(UUID.randomUUID(), () -> revokeHandler.handle(new RevokeSessionRequest(authResponse.sessionId(), authResponse.accountId())));

    assertThat(first).isInstanceOf(RpcOutcome.Success.class);
    assertThat(second).isInstanceOf(RpcOutcome.Success.class);
    assertThat(sessionRepository.findById(authResponse.sessionId()).orElseThrow().isValid(Instant.now())).isFalse();
  }

  @Test
  void revokeSessionRejectsAnotherAccountsSession() {
    String identifier = "user-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);
    var authOutcome =
        (RpcOutcome.Success<?>)
            dispatcher.execute(UUID.randomUUID(), () -> authenticateHandler.handle(new AuthenticateAccountRequest(LoginType.USERNAME, identifier, PASSWORD, null, null, null)));
    var authResponse = (AuthenticateAccountResponse) authOutcome.response();

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> revokeHandler.handle(new RevokeSessionRequest(authResponse.sessionId(), UUID.randomUUID())));

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
