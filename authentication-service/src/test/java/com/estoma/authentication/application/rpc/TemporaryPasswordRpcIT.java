package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordRequest;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TemporaryPasswordRpcIT extends AbstractPostgresIT {

  @Autowired private RpcDispatcher dispatcher;
  @Autowired private SetTemporaryPasswordHandler setHandler;
  @Autowired private RegenerateTemporaryPasswordHandler regenerateHandler;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;

  @Test
  void setTemporaryPasswordSucceedsAndSetsMustChangePassword() {
    UUID accountId = insertAccount();
    var request = new SetTemporaryPasswordRequest(accountId, null, UUID.randomUUID(), "key-" + UUID.randomUUID());

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> setHandler.handle(request));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    var response = ((RpcOutcome.Success<?>) outcome).response();
    assertThat(response).isInstanceOf(com.estoma.authentication.contracts.rpc.SetTemporaryPasswordResponse.class);
    assertThat(credentialRepository.findByAccountId(accountId).orElseThrow().mustChangePassword()).isTrue();
  }

  @Test
  void repeatingTheSameIdempotencyKeyIsUnrecoverable() {
    UUID accountId = insertAccount();
    String key = "key-" + UUID.randomUUID();
    UUID requestedBy = UUID.randomUUID();
    var request = new SetTemporaryPasswordRequest(accountId, null, requestedBy, key);
    dispatcher.execute(UUID.randomUUID(), () -> setHandler.handle(request));

    var second = dispatcher.execute(UUID.randomUUID(), () -> setHandler.handle(request));

    assertThat(second).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) second).code()).isEqualTo(RejectionCode.ALREADY_EXECUTED_SECRET_NOT_RECOVERABLE);
  }

  @Test
  void reusingKeyWithDifferentRequestIsRejected() {
    UUID accountId = insertAccount();
    String key = "key-" + UUID.randomUUID();
    UUID requestedBy = UUID.randomUUID();
    dispatcher.execute(UUID.randomUUID(), () -> setHandler.handle(new SetTemporaryPasswordRequest(accountId, null, requestedBy, key)));

    var second =
        dispatcher.execute(UUID.randomUUID(), () -> setHandler.handle(new SetTemporaryPasswordRequest(accountId, Instant.now().plusSeconds(3600), requestedBy, key)));

    assertThat(second).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) second).code()).isEqualTo(RejectionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
  }

  @Test
  void setTemporaryPasswordRejectsUnknownAccount() {
    var request = new SetTemporaryPasswordRequest(UUID.randomUUID(), null, UUID.randomUUID(), "key-" + UUID.randomUUID());

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> setHandler.handle(request));

    assertThat(outcome).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
  }

  @Test
  void regenerateTemporaryPasswordIssuesANewSecretWithADistinctKey() {
    UUID accountId = insertAccount();
    UUID requestedBy = UUID.randomUUID();
    dispatcher.execute(UUID.randomUUID(), () -> setHandler.handle(new SetTemporaryPasswordRequest(accountId, null, requestedBy, "set-" + UUID.randomUUID())));

    var outcome =
        dispatcher.execute(
            UUID.randomUUID(), () -> regenerateHandler.handle(new RegenerateTemporaryPasswordRequest(accountId, null, requestedBy, "regen-" + UUID.randomUUID())));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
  }

  private UUID insertAccount() {
    Account account = Account.createNew(UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, "temp-" + UUID.randomUUID()), Instant.now());
    accountRepository.insert(account);
    return account.accountId();
  }
}
