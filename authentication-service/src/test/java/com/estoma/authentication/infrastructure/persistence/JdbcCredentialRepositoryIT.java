package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.credential.PasswordHash;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcCredentialRepositoryIT extends AbstractPostgresIT {

  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository repository;

  @Test
  void doesNotExistUntilConfigured() {
    UUID accountId = insertAccount();

    assertThat(repository.existsByAccountId(accountId)).isFalse();

    repository.insert(Credential.configure(accountId, new PasswordHash("hash-1", "ARGON2ID", "{}"), Instant.now()));

    assertThat(repository.existsByAccountId(accountId)).isTrue();
  }

  @Test
  void changedReplacesTheStoredHash() {
    UUID accountId = insertAccount();
    Credential credential = Credential.configure(accountId, new PasswordHash("hash-1", "ARGON2ID", "{}"), Instant.now());
    repository.insert(credential);

    Credential rehashed = credential.changed(new PasswordHash("hash-2", "ARGON2ID", "{}"), Instant.now().plusSeconds(60));
    repository.update(rehashed);

    Credential found = repository.findByAccountId(accountId).orElseThrow();
    assertThat(found.hash().value()).isEqualTo("hash-2");
  }

  private UUID insertAccount() {
    Account account = Account.createNew(
        UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, "cred-" + UUID.randomUUID()), Instant.now());
    accountRepository.insert(account);
    return account.accountId();
  }
}
