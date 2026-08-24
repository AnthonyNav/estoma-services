package com.estoma.authentication.application.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.rpc.GetRecoveryCodeForDeliveryRequest;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.AccountDeactivationReason;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetRecoveryCodeForDeliveryHandlerTest {

  private final PasswordRecoveryRepository recoveries = mock(PasswordRecoveryRepository.class);
  private final RecoveryCodeDeriver codes = mock(RecoveryCodeDeriver.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final PersonStatusProjectionRepository persons = mock(PersonStatusProjectionRepository.class);
  private final GetRecoveryCodeForDeliveryHandler handler =
      new GetRecoveryCodeForDeliveryHandler(recoveries, codes, accounts, persons);

  @Test
  void activeRecoveryAccountAndPersonReturnTheCode() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    PasswordRecoveryRequest recovery = activeRecovery(accountId);
    Account account = activeAccount(accountId, personId);
    when(recoveries.findByIdForUpdate(recovery.recoveryId())).thenReturn(Optional.of(recovery));
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));
    when(persons.findByPersonId(personId)).thenReturn(Optional.of(
        new PersonStatusProjectionRepository.PersonStatusProjectionRecord(personId, "ACTIVE", 4, Instant.now())));
    when(codes.derive(recovery.recoveryId(), recovery.codeDerivationContext())).thenReturn("123456");

    var result = handler.handle(new GetRecoveryCodeForDeliveryRequest(recovery.recoveryId()));

    assertThat(result.response().code()).isEqualTo("123456");
  }

  @Test
  void deactivatedAccountNeverReceivesARecoveryCode() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    PasswordRecoveryRequest recovery = activeRecovery(accountId);
    Account account = activeAccount(accountId, personId).deactivated(Instant.now(), AccountDeactivationReason.SECURITY);
    when(recoveries.findByIdForUpdate(recovery.recoveryId())).thenReturn(Optional.of(recovery));
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> handler.handle(new GetRecoveryCodeForDeliveryRequest(recovery.recoveryId())))
        .isInstanceOf(AuthenticationRejectedException.class)
        .satisfies(error -> assertThat(((AuthenticationRejectedException) error).code())
            .isEqualTo(RejectionCode.RECOVERY_CODE_INVALID));
    verify(codes, never()).derive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void inactivePersonProjectionNeverReceivesARecoveryCode() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    PasswordRecoveryRequest recovery = activeRecovery(accountId);
    Account account = activeAccount(accountId, personId);
    when(recoveries.findByIdForUpdate(recovery.recoveryId())).thenReturn(Optional.of(recovery));
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));
    when(persons.findByPersonId(personId)).thenReturn(Optional.of(
        new PersonStatusProjectionRepository.PersonStatusProjectionRecord(personId, "INACTIVE", 5, Instant.now())));

    assertThatThrownBy(() -> handler.handle(new GetRecoveryCodeForDeliveryRequest(recovery.recoveryId())))
        .isInstanceOf(AuthenticationRejectedException.class)
        .satisfies(error -> assertThat(((AuthenticationRejectedException) error).code())
            .isEqualTo(RejectionCode.RECOVERY_CODE_INVALID));
    verify(codes, never()).derive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void missingPersonProjectionNeverReceivesARecoveryCode() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    PasswordRecoveryRequest recovery = activeRecovery(accountId);
    Account account = activeAccount(accountId, personId);
    when(recoveries.findByIdForUpdate(recovery.recoveryId())).thenReturn(Optional.of(recovery));
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));
    when(persons.findByPersonId(personId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> handler.handle(new GetRecoveryCodeForDeliveryRequest(recovery.recoveryId())))
        .isInstanceOf(AuthenticationRejectedException.class)
        .satisfies(error -> assertThat(((AuthenticationRejectedException) error).code())
            .isEqualTo(RejectionCode.RECOVERY_CODE_INVALID));
    verify(codes, never()).derive(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  private static PasswordRecoveryRequest activeRecovery(UUID accountId) {
    Instant now = Instant.now();
    return PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, now, now.plusSeconds(600));
  }

  private static Account activeAccount(UUID accountId, UUID personId) {
    Account account = Account.createNew(
        accountId,
        personId,
        LoginIdentifier.of(LoginType.USERNAME, "user-" + UUID.randomUUID()),
        Instant.now());
    return account.activated(Instant.now());
  }
}
