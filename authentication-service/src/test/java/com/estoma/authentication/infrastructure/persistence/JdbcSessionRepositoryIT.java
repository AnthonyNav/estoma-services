package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.session.Session;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcSessionRepositoryIT extends AbstractPostgresIT {

  @Autowired private AccountRepository accountRepository;
  @Autowired private SessionRepository repository;

  @Test
  void insertThenFindByRefreshTokenHash() {
    UUID accountId = insertAccount();
    Session session = normalSession(accountId, "hash-" + UUID.randomUUID());

    repository.insert(session);

    assertThat(repository.findByRefreshTokenHash(session.refreshTokenHash())).isPresent();
  }

  @Test
  void rotationUpdatesTokenHashAndExpiry() {
    UUID accountId = insertAccount();
    Session session = normalSession(accountId, "original-hash");
    repository.insert(session);

    Session rotated = session.rotated("rotated-hash", Instant.now(), Instant.now().plusSeconds(1800), Instant.now().plusSeconds(30));
    repository.update(rotated);

    Session found = repository.findById(session.sessionId()).orElseThrow();
    assertThat(found.refreshTokenHash()).isEqualTo("rotated-hash");
  }

  @Test
  void revokeAllActiveForAccountRevokesOnlyValidSessions() {
    UUID accountId = insertAccount();
    Session valid = normalSession(accountId, "valid-" + UUID.randomUUID());
    Session alreadyRevoked = normalSession(accountId, "revoked-" + UUID.randomUUID())
        .revoked("previous", Instant.now());
    repository.insert(valid);
    repository.insert(alreadyRevoked);

    int revokedCount = repository.revokeAllActiveForAccount(accountId, "account_deactivated", Instant.now());

    assertThat(revokedCount).isEqualTo(1);
    assertThat(repository.findById(valid.sessionId()).orElseThrow().isValid(Instant.now())).isFalse();
  }

  private UUID insertAccount() {
    Account account = Account.createNew(
        UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, "sess-" + UUID.randomUUID()), Instant.now());
    accountRepository.insert(account);
    return account.accountId();
  }

  private static Session normalSession(UUID accountId, String hash) {
    Instant now = Instant.now();
    return Session.createNormal(UUID.randomUUID(), accountId, hash, null, null, null, now, now.plusSeconds(900), now.plusSeconds(1800));
  }
}
