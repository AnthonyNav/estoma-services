package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.rpc.RegenerateTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.SetTemporaryPasswordRequest;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class TemporaryPasswordRpcIT extends AbstractPostgresIT {

  private RpcRequestContext context(UUID accountId, UUID actorId, String idempotencyKey) {
    return new RpcRequestContext(UUID.randomUUID(), UUID.randomUUID(), "test", new com.estoma.authentication.contracts.Actor("ACCOUNT", actorId, null, "GLOBAL", null), accountId, idempotencyKey);
  }

  @Autowired private RpcDispatcher dispatcher;
  @Autowired private SetTemporaryPasswordHandler setHandler;
  @Autowired private RegenerateTemporaryPasswordHandler regenerateHandler;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;
  @Autowired private JdbcClient jdbc;

  @Test
  void setTemporaryPasswordSucceedsAndSetsMustChangePassword() {
    UUID accountId = insertAccount();
     var request = new SetTemporaryPasswordRequest(accountId, accountId, "key-" + UUID.randomUUID());

     var outcome = dispatcher.execute(context(accountId, accountId, request.idempotencyKey()), () -> setHandler.handle(context(accountId, accountId, request.idempotencyKey()), request));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    var response = ((RpcOutcome.Success<?>) outcome).response();
    assertThat(response).isInstanceOf(com.estoma.authentication.contracts.rpc.SetTemporaryPasswordResponse.class);
    assertThat(credentialRepository.findByAccountId(accountId).orElseThrow().mustChangePassword()).isTrue();
  }

  @Test
  void temporaryPasswordEventsPreserveIdempotencyAndRequestMetadata() {
    UUID accountId = insertAccount();
    String key = "metadata-key-" + UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    RpcRequestContext requestContext = new RpcRequestContext(
        messageId,
        correlationId,
        "bff",
        new com.estoma.authentication.contracts.Actor("ACCOUNT", actorId, null, "GLOBAL", null),
        accountId,
        key);
    SetTemporaryPasswordRequest request = new SetTemporaryPasswordRequest(accountId, actorId, key);

    dispatcher.execute(requestContext, () -> setHandler.handle(requestContext, request));

    var metadata = jdbc.sql(
            "SELECT correlation_id, causation_id, operation_id, idempotency_key, actor::jsonb ->> 'accountId' "
                + "FROM outbox_messages WHERE message_type = 'TemporaryPasswordSet' AND correlation_id = :correlationId")
        .param("correlationId", correlationId)
        .query((rs, rowNum) -> new Object[] {
            rs.getObject(1), rs.getObject(2), rs.getObject(3), rs.getString(4), rs.getString(5)})
        .single();

    assertThat(metadata[0]).isEqualTo(correlationId);
    assertThat(metadata[1]).isEqualTo(messageId);
    assertThat(metadata[2]).isNull();
    assertThat(metadata[3]).isEqualTo(key);
    assertThat(metadata[4]).isEqualTo(actorId.toString());
  }

  @Test
  void repeatingTheSameIdempotencyKeyIsUnrecoverable() {
    UUID accountId = insertAccount();
    String key = "key-" + UUID.randomUUID();
    UUID requestedBy = UUID.randomUUID();
     var request = new SetTemporaryPasswordRequest(accountId, requestedBy, key);
    dispatcher.execute(context(accountId, requestedBy, key), () -> setHandler.handle(context(accountId, requestedBy, key), request));

    var second = dispatcher.execute(context(accountId, requestedBy, key), () -> setHandler.handle(context(accountId, requestedBy, key), request));

    assertThat(second).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) second).code()).isEqualTo(RejectionCode.ALREADY_EXECUTED_SECRET_NOT_RECOVERABLE);
  }

  @Test
  void reusingKeyWithDifferentRequestIsRejected() {
    UUID accountId = insertAccount();
    String key = "key-" + UUID.randomUUID();
    UUID requestedBy = UUID.randomUUID();
     dispatcher.execute(context(accountId, requestedBy, key), () -> setHandler.handle(context(accountId, requestedBy, key), new SetTemporaryPasswordRequest(accountId, requestedBy, key)));

     UUID differentRequester = UUID.randomUUID();
     var secondRequest = new SetTemporaryPasswordRequest(accountId, differentRequester, key);
     var second =
         dispatcher.execute(context(accountId, differentRequester, key), () -> setHandler.handle(context(accountId, differentRequester, key), secondRequest));

    assertThat(second).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) second).code()).isEqualTo(RejectionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
  }

  @Test
  void setTemporaryPasswordRejectsUnknownAccount() {
     var request = new SetTemporaryPasswordRequest(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID());

      var outcome = dispatcher.execute(context(request.accountId(), request.requestedByAccountId(), request.idempotencyKey()), () -> setHandler.handle(context(request.accountId(), request.requestedByAccountId(), request.idempotencyKey()), request));

    assertThat(outcome).isInstanceOf(RpcOutcome.Rejected.class);
    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
  }

  @Test
  void regenerateTemporaryPasswordIssuesANewSecretWithADistinctKey() {
    UUID accountId = insertAccount();
    UUID requestedBy = UUID.randomUUID();
     var setKey = "set-" + UUID.randomUUID();
     dispatcher.execute(context(accountId, requestedBy, setKey), () -> setHandler.handle(context(accountId, requestedBy, setKey), new SetTemporaryPasswordRequest(accountId, requestedBy, setKey)));

    var outcome =
        dispatcher.execute(
             context(accountId, requestedBy, "regen-key"), () -> regenerateHandler.handle(context(accountId, requestedBy, "regen-key"), new RegenerateTemporaryPasswordRequest(accountId, requestedBy, "regen-key")));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
  }

  private UUID insertAccount() {
    Account account = Account.createNew(UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, "temp-" + UUID.randomUUID()), Instant.now());
    accountRepository.insert(account);
    return account.accountId();
  }
}
