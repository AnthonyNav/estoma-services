package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountResponse;
import com.estoma.authentication.contracts.rpc.RefreshSessionRequest;
import com.estoma.authentication.contracts.rpc.RefreshSessionResponse;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefreshSessionPublicRpcIT extends AbstractPostgresIT {

  private static final String PASSWORD = "correct horse battery staple";

  @Autowired RpcDispatcher dispatcher;
  @Autowired AuthenticateAccountHandler authenticateHandler;
  @Autowired RefreshSessionHandler refreshHandler;
  @Autowired AccountRepository accountRepository;
  @Autowired CredentialRepository credentialRepository;
  @Autowired PasswordHasher passwordHasher;
  @Autowired PersonStatusProjectionRepository personStatusProjectionRepository;

  @Test
  void refreshSucceedsWithoutAccessTokenActorOrTargetAccount() {
    String identifier = "refresh-public-" + UUID.randomUUID();
    insertActiveAccountWithPassword(identifier, PASSWORD);

    var authenticated = (AuthenticateAccountResponse) ((RpcOutcome.Success<?>) dispatcher.execute(
        anonymousContext(),
        () -> authenticateHandler.handle(new AuthenticateAccountRequest(
            LoginType.USERNAME, identifier, PASSWORD, null, null, null)))).response();

    RpcRequestContext refreshContext = anonymousContext();
    var refreshed = dispatcher.execute(
        refreshContext,
        () -> refreshHandler.handle(
            refreshContext,
            new RefreshSessionRequest(authenticated.sessionId(), authenticated.refreshToken())));

    assertThat(refreshed).isInstanceOf(RpcOutcome.Success.class);
    var response = (RefreshSessionResponse) ((RpcOutcome.Success<?>) refreshed).response();
    assertThat(response.accessToken()).isNotBlank();
    assertThat(response.refreshToken()).isNotBlank().isNotEqualTo(authenticated.refreshToken());
  }

  private static RpcRequestContext anonymousContext() {
    return new RpcRequestContext(
        UUID.randomUUID(), UUID.randomUUID(), "bff",
        new Actor("ANONYMOUS", null, null, "PUBLIC", null), null, null);
  }

  private UUID insertActiveAccountWithPassword(String identifier, String password) {
    Account account = Account.createNew(
        UUID.randomUUID(), UUID.randomUUID(),
        LoginIdentifier.of(LoginType.USERNAME, identifier), Instant.now());
    accountRepository.insert(account);
    personStatusProjectionRepository.upsertIfNewer(
        account.personId(), "ACTIVE", 1, Instant.now());
    credentialRepository.insert(Credential.configure(
        account.accountId(), passwordHasher.hash(password), Instant.now()));
    Account activated = account.activated(Instant.now());
    accountRepository.update(activated, account.version());
    return account.accountId();
  }
}
