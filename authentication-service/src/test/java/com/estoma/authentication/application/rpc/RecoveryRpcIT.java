package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.TokenHasher;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.rpc.ChangePasswordRequest;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeRequest;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RecoveryRpcIT extends AbstractPostgresIT {

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

  @Test
  void validateRecoveryCodeAcceptsTheCorrectCode() {
    UUID accountId = insertActiveAccountWithPassword();
    String code = uniqueCode("100001");
    var recovery = insertRecoveryRequest(accountId, code);

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> validateHandler.handle(new ValidateRecoveryCodeRequest(recovery.recoveryId(), code)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(((com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse) ((RpcOutcome.Success<?>) outcome).response()).result()).isEqualTo("VALID");
  }

  @Test
  void validateRecoveryCodeRejectsAWrongCodeWithoutThrowing() {
    UUID accountId = insertActiveAccountWithPassword();
    var recovery = insertRecoveryRequest(accountId, uniqueCode("100002"));

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> validateHandler.handle(new ValidateRecoveryCodeRequest(recovery.recoveryId(), "000000")));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(((com.estoma.authentication.contracts.rpc.ValidateRecoveryCodeResponse) ((RpcOutcome.Success<?>) outcome).response()).result()).isEqualTo("INVALID");
  }

  @Test
  void resetPasswordChangesTheCredentialAndConsumesTheRequest() {
    UUID accountId = insertActiveAccountWithPassword();
    String code = uniqueCode("200001");
    var recovery = insertRecoveryRequest(accountId, code);

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> resetHandler.handle(new ResetPasswordRequest(recovery.recoveryId(), code, NEW_PASSWORD)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(passwordHasher.matches(NEW_PASSWORD, credentialRepository.findByAccountId(accountId).orElseThrow().hash())).isTrue();
    assertThat(recoveryRepository.findById(recovery.recoveryId()).orElseThrow().isUsed()).isTrue();
  }

  @Test
  void resetPasswordCannotBeRepeatedWithTheSameCode() {
    UUID accountId = insertActiveAccountWithPassword();
    String code = uniqueCode("200002");
    var recovery = insertRecoveryRequest(accountId, code);
    dispatcher.execute(UUID.randomUUID(), () -> resetHandler.handle(new ResetPasswordRequest(recovery.recoveryId(), code, NEW_PASSWORD)));

    var second = dispatcher.execute(UUID.randomUUID(), () -> resetHandler.handle(new ResetPasswordRequest(recovery.recoveryId(), code, "yet-another-password")));

    assertThat(((RpcOutcome.Rejected<?>) second).code()).isEqualTo(RejectionCode.RECOVERY_TOKEN_ALREADY_USED);
  }

  @Test
  void resetPasswordRejectsAnExpiredRequest() {
    UUID accountId = insertActiveAccountWithPassword();
    Instant past = Instant.now().minusSeconds(60);
    String code = uniqueCode("200003");
    var recovery = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, tokenHasher.sha256Hex(code), past.minusSeconds(1800), past);
    recoveryRepository.insert(recovery);

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> resetHandler.handle(new ResetPasswordRequest(recovery.recoveryId(), code, NEW_PASSWORD)));

    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.RECOVERY_TOKEN_EXPIRED);
  }

  @Test
  void changePasswordSucceedsWithCorrectCurrentPassword() {
    UUID accountId = insertActiveAccountWithPassword();

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> changeHandler.handle(new ChangePasswordRequest(accountId, OLD_PASSWORD, NEW_PASSWORD)));

    assertThat(outcome).isInstanceOf(RpcOutcome.Success.class);
    assertThat(passwordHasher.matches(NEW_PASSWORD, credentialRepository.findByAccountId(accountId).orElseThrow().hash())).isTrue();
  }

  @Test
  void changePasswordRejectsWrongCurrentPassword() {
    UUID accountId = insertActiveAccountWithPassword();

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> changeHandler.handle(new ChangePasswordRequest(accountId, "not-the-current-password", NEW_PASSWORD)));

    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.INVALID_CREDENTIALS);
  }

  @Test
  void changePasswordRejectsReusingTheSamePassword() {
    UUID accountId = insertActiveAccountWithPassword();

    var outcome = dispatcher.execute(UUID.randomUUID(), () -> changeHandler.handle(new ChangePasswordRequest(accountId, OLD_PASSWORD, OLD_PASSWORD)));

    assertThat(((RpcOutcome.Rejected<?>) outcome).code()).isEqualTo(RejectionCode.INVALID_PASSWORD);
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
}
