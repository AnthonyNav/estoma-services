package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import com.estoma.authentication.domain.session.Session;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class RecoveryRpcIT extends AbstractPostgresIT {

  private RpcRequestContext context(UUID accountId) {
    return new RpcRequestContext(UUID.randomUUID(), UUID.randomUUID(), "test", new com.estoma.authentication.contracts.Actor("ACCOUNT", accountId, null, "SELF", null), accountId, null);
  }

  private static final String OLD_PASSWORD = "old-password-123";
  private static final String NEW_PASSWORD = "new-password-456";

  @Autowired private RpcDispatcher dispatcher;
  @Autowired private ValidateRecoveryCodeHandler validateHandler;
  @Autowired private ResetPasswordHandler resetHandler;
  @Autowired private ChangePasswordHandler changeHandler;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;
  @Autowired private PasswordRecoveryRepository recoveryRepository;
  @Autowired private PasswordHasher passwordHasher;
  @Autowired private TokenHasher tokenHasher;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcClient jdbc;

  @Test
  void validateRecoveryCodeAcceptsTheCorrectCode() {
    UUID accountId = insertActiveAccountWithPassword();
    String code = uniqueCode("100001");
    var recovery = insertRecoveryRequest(accountId, code);

    var outcome = dispatcher.execute(context(accountId), () -> validateHandler.handle(new ValidateRecoveryCodeRequest(LoginType.USERNAME, accountLogin(accountId), code)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(((com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse) ((RpcOutcome.Success<?>) outcome).response()).result()).isEqualTo("VALID");
  }

  @Test
  void validateRecoveryCodeRejectsAWrongCodeWithoutThrowing() {
    UUID accountId = insertActiveAccountWithPassword();
    var recovery = insertRecoveryRequest(accountId, uniqueCode("100002"));

    var outcome = dispatcher.execute(context(accountId), () -> validateHandler.handle(new ValidateRecoveryCodeRequest(LoginType.USERNAME, accountLogin(accountId), "000000")));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(((com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse) ((RpcOutcome.Success<?>) outcome).response()).result()).isEqualTo("INVALID");
  }

  @Test
  void fifthInvalidRecoveryCodeExhaustsTheRequest() {
    UUID accountId = insertActiveAccountWithPassword();
    PasswordRecoveryRequest recovery = insertRecoveryRequest(accountId, uniqueCode("100003"));

    for (int attempt = 0; attempt < 5; attempt++) {
      var outcome = dispatcher.execute(
          context(accountId),
          () -> validateHandler.handle(new ValidateRecoveryCodeRequest(LoginType.USERNAME, accountLogin(accountId), "000000")));
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
    String code = uniqueCode("200001");
    var recovery = insertRecoveryRequest(accountId, code);

    var outcome = dispatcher.execute(context(accountId), () -> resetHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, accountLogin(accountId), code, NEW_PASSWORD)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(passwordHasher.matches(NEW_PASSWORD, credentialRepository.findByAccountId(accountId).orElseThrow().hash())).isTrue();
    assertThat(recoveryRepository.findById(recovery.recoveryId()).orElseThrow().isUsed()).isTrue();
  }

  @Test
  void resetPasswordCannotBeRepeatedWithTheSameCode() {
    UUID accountId = insertActiveAccountWithPassword();
    String code = uniqueCode("200002");
    var recovery = insertRecoveryRequest(accountId, code);
    dispatcher.execute(context(accountId), () -> resetHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, accountLogin(accountId), code, NEW_PASSWORD)));

    var second = dispatcher.execute(context(accountId), () -> resetHandler.handle(new ResetPasswordRequest(LoginType.USERNAME, accountLogin(accountId), code, "yet-another-password")));

    assertThat(((RpcOutcome.Rejected<?>) second).code()).isEqualTo(RejectionCode.RECOVERY_NOT_AVAILABLE);
  }

  @Test
  void resetPasswordRejectsAnExpiredRequest() {
    UUID accountId = insertActiveAccountWithPassword();
    Instant past = Instant.now().minusSeconds(60);
    String code = uniqueCode("200003");
    var recovery = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, tokenHasher.sha256Hex(code), past.minusSeconds(1800), past);
    recoveryRepository.insert(recovery);

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
  }

  private String uniqueCode(String base) {
    return base + "-" + UUID.randomUUID();
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

  private PasswordRecoveryRequest insertRecoveryRequest(UUID accountId, String code) {
    PasswordRecoveryRequest request = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, tokenHasher.sha256Hex(code), Instant.now(), Instant.now().plusSeconds(1800));
    recoveryRepository.insert(request);
    return request;
  }

  private String accountLogin(UUID accountId) {
    return accountRepository.findById(accountId).orElseThrow().loginIdentifier().value();
  }

  private long eventCount(UUID accountId, String messageType) {
    return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id = :accountId AND message_type = :messageType")
        .param("accountId", accountId)
        .param("messageType", messageType)
        .query(Long.class)
        .single();
  }

  private static Session normalSession(UUID accountId) {
    Instant now = Instant.now();
    return Session.createNormal(UUID.randomUUID(), accountId, "hash-" + UUID.randomUUID(), null, null, null, now, now.plusSeconds(900), now.plusSeconds(1800));
  }
}
