package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.AccountDeactivationReason;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcAccountRepositoryIT extends AbstractPostgresIT {

  @Autowired private AccountRepository repository;

  @Test
  void insertThenFindByIdRoundTrips() {
    Account account = newAccount("A" + UUID.randomUUID());

    repository.insert(account);
    Account found = repository.findById(account.accountId()).orElseThrow();

    assertThat(found.accountId()).isEqualTo(account.accountId());
    assertThat(found.loginIdentifier().normalized()).isEqualTo(account.loginIdentifier().normalized());
    assertThat(found.version()).isEqualTo(1);
  }

  @Test
  void findByLoginIdentifierUsesNormalizedForm() {
    Account account = newAccount("Matricula-" + UUID.randomUUID());
    repository.insert(account);

    Account found =
        repository.findByLoginIdentifier(LoginType.MATRICULA, account.loginIdentifier().normalized()).orElseThrow();

    assertThat(found.accountId()).isEqualTo(account.accountId());
  }

  @Test
  void insertingDuplicateAccountIdIsRejected() {
    Account account = newAccount("dup-id-" + UUID.randomUUID());
    repository.insert(account);
    Account sameId = Account.createNew(account.accountId(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, "other-" + UUID.randomUUID()), Instant.now());

    assertThatThrownBy(() -> repository.insert(sameId))
        .isInstanceOf(AuthenticationRejectedException.class)
        .satisfies(e -> assertThat(((AuthenticationRejectedException) e).code()).isEqualTo(RejectionCode.ACCOUNT_ALREADY_EXISTS));
  }

  @Test
  void insertingDuplicateLoginIdentifierIsRejected() {
    String identifier = "shared-" + UUID.randomUUID();
    repository.insert(newAccount(identifier));
    Account other = Account.createNew(UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, identifier), Instant.now());

    assertThatThrownBy(() -> repository.insert(other))
        .isInstanceOf(AuthenticationRejectedException.class)
        .satisfies(e -> assertThat(((AuthenticationRejectedException) e).code()).isEqualTo(RejectionCode.LOGIN_IDENTIFIER_ALREADY_USED));
  }

  @Test
  void updateWithCorrectExpectedVersionSucceeds() {
    Account account = newAccount("activate-" + UUID.randomUUID());
    repository.insert(account);

    Account activated = account.activated(Instant.now());
    int rowsAffected = repository.update(activated, account.version());

    assertThat(rowsAffected).isEqualTo(1);
    assertThat(repository.findById(account.accountId()).orElseThrow().status()).isEqualTo(com.estoma.authentication.contracts.AccountStatus.ACTIVE);
  }

  @Test
  void updateWithStaleExpectedVersionAffectsNoRows() {
    Account account = newAccount("stale-" + UUID.randomUUID());
    repository.insert(account);
    Account activated = account.activated(Instant.now());
    repository.update(activated, account.version());

    int rowsAffected = repository.update(activated.deactivated(Instant.now(), AccountDeactivationReason.ADMINISTRATIVE), account.version());

    assertThat(rowsAffected).isZero();
  }

  private Account newAccount(String identifierSuffix) {
    return Account.createNew(
        UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, identifierSuffix), Instant.now());
  }
}
