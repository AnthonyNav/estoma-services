package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository.PersonStatusProjectionRecord;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.rpc.ResetPasswordRequest;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import com.estoma.authentication.domain.credential.PasswordHash;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResetPasswordHandlerTest {

  @Test
  void zeroRowAccountUpdateFailsTechnicallyBeforeInvalidatingRecoveryOrSessions() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    UUID recoveryId = UUID.randomUUID();
    AccountRepository accounts = mock(AccountRepository.class);
    PasswordRecoveryRepository recoveries = mock(PasswordRecoveryRepository.class);
    CredentialRepository credentials = mock(CredentialRepository.class);
    PersonStatusProjectionRepository statuses = mock(PersonStatusProjectionRepository.class);
    SessionRepository sessions = mock(SessionRepository.class);
    PasswordHasher hasher = mock(PasswordHasher.class);
    PasswordPolicy policy = mock(PasswordPolicy.class);
    RecoveryCodeDeriver deriver = mock(RecoveryCodeDeriver.class);
    Account account = mock(Account.class);
    Instant now = Instant.now();

    when(account.accountId()).thenReturn(accountId);
    when(account.personId()).thenReturn(personId);
    when(account.version()).thenReturn(3);
    when(account.isActive()).thenReturn(true);
    when(account.withAttemptsReset(org.mockito.ArgumentMatchers.any())).thenReturn(account);
    when(accounts.findByLoginIdentifierForUpdate(LoginType.USERNAME, "user")).thenReturn(Optional.of(account));
    when(statuses.findByPersonId(personId)).thenReturn(Optional.of(mockStatus("ACTIVE")));
    when(recoveries.findActiveByAccountIdForUpdate(accountId)).thenReturn(Optional.of(
        PasswordRecoveryRequest.createNew(recoveryId, accountId, now, now.plusSeconds(60))));
    when(deriver.matches(recoveryId, "HMAC_SHA256_V1", "123456")).thenReturn(true);
    when(recoveries.markUsedIfValid(org.mockito.ArgumentMatchers.eq(recoveryId), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
    when(credentials.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(credentials.existsByAccountId(accountId)).thenReturn(false);
    when(hasher.hash("new-password")).thenReturn(mock(PasswordHash.class));
    when(accounts.update(account, 3)).thenReturn(0);

    ResetPasswordHandler handler = new ResetPasswordHandler(
        recoveries, accounts, statuses, credentials, sessions, hasher, policy, deriver);

    assertThatThrownBy(() -> handler.handle(new ResetPasswordRequest(LoginType.USERNAME, "user", "123456", "new-password")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("reset password account update affected zero rows");
    verify(recoveries).markUsedIfValid(org.mockito.ArgumentMatchers.eq(recoveryId), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(sessions);
    verify(recoveries, org.mockito.Mockito.never()).invalidateActiveForAccount(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void unsupportedDerivationContextIsUnavailable() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    AccountRepository accounts = mock(AccountRepository.class);
    PasswordRecoveryRepository recoveries = mock(PasswordRecoveryRepository.class);
    PersonStatusProjectionRepository statuses = mock(PersonStatusProjectionRepository.class);
    RecoveryCodeDeriver deriver = mock(RecoveryCodeDeriver.class);
    Account account = mock(Account.class);
    Instant now = Instant.now();

    when(account.accountId()).thenReturn(accountId);
    when(account.personId()).thenReturn(personId);
    when(account.isActive()).thenReturn(true);
    when(accounts.findByLoginIdentifierForUpdate(LoginType.USERNAME, "user")).thenReturn(Optional.of(account));
    when(statuses.findByPersonId(personId)).thenReturn(Optional.of(mockStatus("ACTIVE")));
    when(recoveries.findActiveByAccountIdForUpdate(accountId)).thenReturn(Optional.of(
        PasswordRecoveryRequest.reconstitute(UUID.randomUUID(), accountId, "legacy-sha256", now, now.plusSeconds(60), null,
            PasswordRecoveryRequest.Status.ACTIVE, null, 0)));

    ResetPasswordHandler handler = new ResetPasswordHandler(
        recoveries, accounts, statuses, mock(CredentialRepository.class), mock(SessionRepository.class),
        mock(PasswordHasher.class), mock(PasswordPolicy.class), deriver);

    assertThatThrownBy(() -> handler.handle(new ResetPasswordRequest(LoginType.USERNAME, "user", "123456", "new-password")))
        .isInstanceOf(AuthenticationRejectedException.class)
        .hasMessageContaining("unsupported");
    verifyNoInteractions(deriver);
  }

  private static PersonStatusProjectionRecord mockStatus(String status) {
    return new PersonStatusProjectionRecord(UUID.randomUUID(), status, 1, Instant.now());
  }
}
